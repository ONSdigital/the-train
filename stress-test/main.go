package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"math/rand/v2"
	"mime/multipart"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	defaultNumFiles       = 500
	defaultFileSize       = 5
	defaultSetupBatchSize = 100
	zebedeeThreadPool     = 10 // number of concurrent threads in zebedee sending files to the train
)

var (
	DefaultTrainURL = "http://localhost:8084/"
)

func main() {
	lh := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})
	slog.SetDefault(slog.New(lh))

	startTime := time.Now()

	flagBatchSize := flag.Int("batch", defaultSetupBatchSize, "number of files in each batch on setup")
	flagCleanOnly := flag.Bool("clean", false, "clean whole test directory and exit")
	flagCopyFiles := flag.Int("cp", defaultNumFiles, "number of files to copy via manifest")
	flagDebug := flag.Bool("debug", false, "debug mode")
	flagFileSize := flag.Int("fsize", defaultFileSize, "file size in kb")
	flagId := flag.String("id", "", "use existing test directory id (skips setup)")
	flagNoCleanup := flag.Bool("noclean", false, "do not clean up test files")
	flagPublishFiles := flag.Int("pf", defaultNumFiles, "number of files to send during publish")
	flagSetupFiles := flag.Int("sf", defaultNumFiles, "number of files to send on setup")
	flagSo := flag.Bool("so", false, "only run setup stage")
	flagTrainUrl := flag.String("url", DefaultTrainURL, "train url")
	flagVersion := flag.Int("v", 1, "version to create on publish")

	flag.Parse()

	config := runConfig{
		BatchSize:    *flagBatchSize,
		CleanOnly:    *flagCleanOnly,
		Cleanup:      !(*flagNoCleanup),
		CopyFiles:    *flagCopyFiles,
		FileSize:     *flagFileSize * 1024,
		ID:           *flagId,
		PublishFiles: *flagPublishFiles,
		SetupFiles:   *flagSetupFiles,
		SetupOnly:    *flagSo,
		TrainURL:     *flagTrainUrl,
		Version:      *flagVersion,
	}

	if *flagDebug {
		logHandler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelDebug})
		slog.SetDefault(slog.New(logHandler))
		slog.Debug("debug mode")
	}

	err := run(config)
	if err != nil {
		slog.Error("fatal error", "err", err.Error())
		os.Exit(1)
	}

	endTime := time.Now()
	slog.Info("complete", "runTime", endTime.Sub(startTime).Round(time.Millisecond).Milliseconds())
}

type runConfig struct {
	BatchSize    int
	CleanOnly    bool
	Cleanup      bool
	CopyFiles    int
	FileSize     int
	ID           string
	PublishFiles int
	SetupOnly    bool
	SetupFiles   int
	Version      int
	TrainURL     string
}

func run(config runConfig) error {
	slog.Debug("running command", "config", config)
	train, err := url.Parse(config.TrainURL)
	if err != nil {
		return fmt.Errorf("failed to parse train url: [%w]", err)
	}
	if config.CleanOnly {
		return cleanup(train, "/test")
	}

	testId := config.ID
	if config.SetupOnly || testId == "" {
		newTestId, err := initTestDir(train, config.SetupFiles, config.FileSize, config.BatchSize)
		if err != nil {
			return err
		}
		testId = newTestId
	}
	if config.SetupOnly {
		return nil
	}

	err = publishingSimulation(train, testId, config.CopyFiles, config.Version, config.PublishFiles, config.FileSize)
	if err != nil {
		return err
	}

	if config.Cleanup {
		cleanup(train, filepath.Join("/test", testId))
	}
	return nil
}

func initTestDir(train *url.URL, numFiles, fileSize, batchSize int) (string, error) {
	var testId, testDir string
	mu := sync.Mutex{}
	mu.Lock()
	wg := sync.WaitGroup{}
	var anyError error = nil
	slog.Info("initialising test directory", "numFiles", numFiles, "fileSize", fileSize)
	for sn := 0; sn < numFiles; sn += batchSize {
		wg.Add(1)
		go func() {
			defer wg.Done()
			transactionID, err := openTransaction(train)
			if err != nil {
				slog.Error("failed to open transaction", "err", err.Error())
				anyError = err
			}
			slog.Debug("init transaction created", "id", transactionID, "batch_start", sn)

			if sn == 0 {
				// reuse the initial transactionId as a test dir
				testId = transactionID
				testDir = "/test/" + testId
				mu.Unlock()
			} else {
				mu.Lock()
				mu.Unlock()
				slog.Debug("reusing test dir from first batch", "id", transactionID, "batch_start", sn, "testDir", testDir)
			}

			for i := sn; i < numFiles && i < sn+batchSize; i++ {
				subdir := genSubDirName(i)

				data, err := genFileContent(path.Join(testDir, subdir), subdir, fileSize)
				if err != nil {
					anyError = fmt.Errorf("failed to generate test data: %w", err)
				}
				slog.Debug("generated test data", "subdir", subdir, "length", len(data))

				uri := path.Join(testDir, subdir, "data.json")
				err = sendFile(train, transactionID, uri, data)
				if err != nil {
					slog.Error("failed to send file", "err", err.Error())
					anyError = err
				}
				slog.Debug("sent test data", "subdir", subdir, "length", len(data))
				if i%100 == 99 {
					slog.Debug("sent another hundred files", "total", i+1)
				}
			}

			err = commitTransaction(train, transactionID)
			if err != nil {
				slog.Error("failed to commit transaction", "err", err.Error())
				anyError = err
			}
			slog.Debug("transaction committed", "id", transactionID, "batch_start", sn)
		}()
	}
	wg.Wait()
	if anyError != nil {
		return "", anyError
	}
	slog.Info("initialised test directory", testId, testId)
	return testId, nil
}

func publishingSimulation(train *url.URL, testId string, filesToCopy, version, filesToPublish, fileSize int) error {
	testDir := "/test/" + testId

	slog.Info("starting publishing simulation", "filesToCopy", filesToCopy, "filesToPublish", filesToPublish, "fileSize", fileSize, "version", version, "testId", testId)

	startPrePublish := time.Now()
	transactionID, err := openTransaction(train)
	if err != nil {
		slog.Error("failed to open publishing transaction", "err", err.Error())
		return err
	}
	slog.Info("publishing transaction created", "id", transactionID)

	// Send a pre-publishing manifest of files to be copied (versioned)
	mf := manifest{
		FilesToCopy:  make([]fileToCopy, 0),
		UrisToDelete: make([]string, 0),
	}
	for i := 0; i < filesToCopy; i++ {
		subdir := genSubDirName(i)
		mf.FilesToCopy = append(mf.FilesToCopy, fileToCopy{
			Source: path.Join(testDir, subdir, "data.json"),
			Target: path.Join(testDir, subdir, fmt.Sprintf("v%d", version), "data.json"),
		})
	}

	slog.Debug("created pre-publishing manifest", "mf", mf)

	err = sendManifest(train, transactionID, &mf)
	if err != nil {
		slog.Error("failed to send manifest", "err", err.Error())
		return err
	}
	endPrePublish := time.Now()
	slog.Info("published pre-publishing manifest")

	slog.Info("pre-publish stage complete", "runTime", endPrePublish.Sub(startPrePublish).Round(time.Millisecond).Milliseconds())

	// Publish some new files

	startPublish := time.Now()
	fileNumberChannel := make(chan int, zebedeeThreadPool)
	wg := sync.WaitGroup{}
	var anyError error = nil

	for sender := 0; sender < zebedeeThreadPool; sender++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range fileNumberChannel {
				subdir := genSubDirName(i)

				data, err := genFileContent(path.Join(testDir, subdir), subdir, fileSize)
				if err != nil {
					anyError = fmt.Errorf("failed to generate test data for file publish: %w", err)
				}
				slog.Debug("generated publishing test data", "subdir", subdir, "length", len(data))

				uri := path.Join(testDir, subdir, "data.json")
				err = sendFile(train, transactionID, uri, data)
				if err != nil {
					slog.Error("failed to publish file", "err", err.Error())
					anyError = err
				}
				slog.Debug("published test data", "subdir", subdir, "length", len(data))
			}
		}()
	}

	for i := 0; i < filesToPublish; i++ {
		fileNumberChannel <- i
	}
	close(fileNumberChannel)

	wg.Wait()
	if anyError != nil {
		return anyError
	}

	slog.Info("published test files", "numFiles", filesToPublish)

	err = commitTransaction(train, transactionID)
	if err != nil {
		slog.Error("failed to commit transaction", "err", err.Error())
		return err
	}
	slog.Info("publishing transaction committed", "id", transactionID)

	endPublish := time.Now()
	slog.Info("publish stage complete", "runTime", endPublish.Sub(startPublish).Round(time.Millisecond).Milliseconds())

	slog.Info("publishing simulation complete")

	return nil
}

func cleanup(train *url.URL, dirToClean string) error {
	slog.Info("cleanup of test directory", "dir", dirToClean)
	transactionID, err := openTransaction(train)
	if err != nil {
		slog.Error("failed to open cleanup transaction", "err", err.Error())
		return err
	}
	slog.Debug("cleanup transaction created", "id", transactionID)

	// Send a cleanup manifest of uri to be deleted
	mf := manifest{
		FilesToCopy:  make([]fileToCopy, 0),
		UrisToDelete: []string{dirToClean},
	}
	err = sendManifest(train, transactionID, &mf)
	if err != nil {
		slog.Error("failed to send cleanup manifest", "err", err.Error())
		return err
	}
	slog.Debug("sent cleanup manifest", "mf", mf)

	err = commitTransaction(train, transactionID)
	if err != nil {
		slog.Error("failed to commit cleanup transaction", "err", err.Error())
		return err
	}
	slog.Debug("cleanup transaction committed", "id", transactionID)

	slog.Info("cleanup completed", "dir", dirToClean)
	return nil
}

func genSubDirName(n int) string {
	nstr := []byte(fmt.Sprintf("%05d", n))
	for i := 0; i < len(nstr); i++ {
		nstr[i] = nstr[i] + 49
	}
	return string(nstr)
}

type openTransactionResponse struct {
	Transaction struct {
		ID string `json:"id"`
	} `json:"transaction"`
}

func openTransaction(train *url.URL) (string, error) {
	newUrl := *train
	newUrl.Path = path.Join(newUrl.Path, "begin")
	slog.Debug("opening transaction", "url", newUrl.String())

	r, err := http.Post(newUrl.String(), "", http.NoBody)
	if err != nil {
		return "", fmt.Errorf("failed to call train: [%w]", err)
	}
	if r.StatusCode != http.StatusOK {
		return "", fmt.Errorf("unexpected status code: [%d] %s", r.StatusCode, http.StatusText(r.StatusCode))
	}
	body, err := io.ReadAll(r.Body)
	if err != nil {
		return "", fmt.Errorf("failed to read train response: [%w]", err)
	}
	tr := openTransactionResponse{}
	err = json.Unmarshal(body, &tr)
	if err != nil {
		return "", fmt.Errorf("failed to unmarshal train response: [%w]", err)
	}

	transactionID := tr.Transaction.ID
	slog.Debug("opened transaction", "id", transactionID)

	return tr.Transaction.ID, nil
}

type commitTransactionResponse struct {
	Message     string `json:"message"`
	Transaction struct {
		ID string `json:"id"`
	} `json:"transaction"`
}

func commitTransaction(train *url.URL, transactionID string) error {
	newUrl := *train
	newUrl.Path = path.Join(newUrl.Path, "commit")
	q := newUrl.Query()
	q.Set("transactionId", transactionID)
	newUrl.RawQuery = q.Encode()

	slog.Debug("committing transaction", "url", newUrl.String())

	r, err := http.Post(newUrl.String(), "", http.NoBody)
	if err != nil {
		return fmt.Errorf("failed to call train: [%w]", err)
	}
	if r.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected status code: [%d] %s", r.StatusCode, http.StatusText(r.StatusCode))
	}
	body, err := io.ReadAll(r.Body)
	if err != nil {
		return fmt.Errorf("failed to read train response: [%w]", err)
	}
	tr := commitTransactionResponse{}
	err = json.Unmarshal(body, &tr)
	if err != nil {
		return fmt.Errorf("failed to unmarshal train response: [%w]", err)
	}

	slog.Debug("committed transaction", "id", transactionID)

	return nil
}

type sendFileResponse struct {
	Message     string `json:"message"`
	Transaction struct {
		ID string `json:"id"`
	} `json:"transaction"`
}

func sendFile(train *url.URL, transactionID, uri string, data []byte) error {
	newUrl := *train
	newUrl.Path = path.Join(newUrl.Path, "publish")
	q := newUrl.Query()
	q.Set("transactionId", transactionID)
	q.Set("uri", uri)
	newUrl.RawQuery = q.Encode()

	buf := new(bytes.Buffer)
	mpw := multipart.NewWriter(buf)
	part, err := mpw.CreateFormFile("file", filepath.Base(uri))
	if err != nil {
		return fmt.Errorf("failed to create form file: [%w]", err)
	}
	_, err = part.Write(data)
	if err != nil {
		return fmt.Errorf("failed to write form file: [%w]", err)
	}
	contentType := mpw.FormDataContentType()
	err = mpw.Close()
	if err != nil {
		return fmt.Errorf("failed to close form file: [%w]", err)
	}

	slog.Debug("sending file", "url", newUrl.String())

	r, err := http.Post(newUrl.String(), contentType, buf)
	if err != nil {
		return fmt.Errorf("failed to call train: [%w]", err)
	}
	if r.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected status code: [%d] %s", r.StatusCode, http.StatusText(r.StatusCode))
	}
	body, err := io.ReadAll(r.Body)
	if err != nil {
		return fmt.Errorf("failed to read train response: [%w]", err)
	}
	tr := sendFileResponse{}
	err = json.Unmarshal(body, &tr)
	if err != nil {
		return fmt.Errorf("failed to unmarshal train response: [%w]", err)
	}

	slog.Debug("sent file", "id", transactionID, "uri", uri)

	return nil
}

type pageData struct {
	Sections    []string        `json:"sections"`
	Markdown    []string        `json:"markdown"`
	Links       []string        `json:"links"`
	Type        string          `json:"type"`
	URI         string          `json:"uri"`
	Description pageDescription `json:"description"`
}

type pageDescription struct {
	Title           string   `json:"title"`
	Summary         string   `json:"summary"`
	Keywords        []string `json:"keywords"`
	MetaDescription string   `json:"metaDescription"`
	ReleaseDate     string   `json:"releaseDate"`
	Unit            string   `json:"unit"`
	PreUnit         string   `json:"preUnit"`
	Source          string   `json:"source"`
}

func genFileContent(uri, name string, size int) ([]byte, error) {
	rd := time.Now().UTC().Format(time.RFC3339)
	page := pageData{
		Sections: make([]string, 0),
		Markdown: []string{"This is test data for a stress test of The-Train. It is not production data and should be disregarded.\n\n" + genContentPadding(size-489)},
		Links:    make([]string, 0),
		Type:     "static_landing_page",
		URI:      uri,
		Description: pageDescription{
			Title:           "Stress Test page for " + strings.ToUpper(name),
			Summary:         "A sample for stress testing",
			Keywords:        make([]string, 0),
			MetaDescription: "A sample for stress testing",
			ReleaseDate:     rd,
		},
	}

	return json.Marshal(page)
}

func genContentPadding(size int) string {
	if size < 1 {
		return ""
	}
	padding := make([]byte, size)
	next := "C"
	sentsLeft, wordsLeft, chsLeft := 0, 0, 0
	for i, _ := range padding {
		if chsLeft == 0 {
			chsLeft = rand.IntN(10) + 5
			if i > 0 {
				next = " "
				wordsLeft--
			}
		}
		if wordsLeft == 0 {
			wordsLeft = rand.IntN(10) + 5
			if i > 0 {
				next = "."
				sentsLeft--
			}
		}
		if sentsLeft == 0 {
			sentsLeft = rand.IntN(5) + 4
			if i > 0 {
				next = "n"
			}
		}
		switch next {
		case "n":
			padding[i] = '.'
			next = "n2"
		case "n2":
			padding[i] = byte('\n')
			next = "C"
		case "_":
			padding[i] = byte(' ')
			next = "C"
		case "C":
			padding[i] = byte(rand.IntN(26) + 65)
			next = ""
		case " ":
			padding[i] = byte(' ')
			next = ""
		case ".":
			padding[i] = byte('.')
			next = "_"
		default:
			padding[i] = byte(rand.IntN(26) + 97)
		}
		chsLeft--
	}
	if size > 4 {
		// make sure it ends cleanly.
		padding[size-4] = byte(rand.IntN(26) + 97)
		padding[size-3] = byte(rand.IntN(26) + 97)
		padding[size-2] = byte(rand.IntN(26) + 97)
		padding[size-1] = byte('.')
	}
	return string(padding)
}

type manifest struct {
	FilesToCopy  []fileToCopy `json:"filesToCopy"`
	UrisToDelete []string     `json:"urisToDelete"`
}

type fileToCopy struct {
	Source string `json:"source"`
	Target string `json:"target"`
}

type sendManifestResponse struct {
	Message     string `json:"message"`
	Transaction struct {
		ID string `json:"id"`
	} `json:"transaction"`
}

func sendManifest(train *url.URL, transactionID string, mf *manifest) error {
	newUrl := *train
	newUrl.Path = path.Join(newUrl.Path, "CommitManifest")
	q := newUrl.Query()
	q.Set("transactionId", transactionID)
	newUrl.RawQuery = q.Encode()

	payload, err := json.Marshal(mf)
	if err != nil {
		return fmt.Errorf("failed to marshal manifest: [%w]", err)
	}

	slog.Debug("sending manifest", "url", newUrl.String())
	r, err := http.Post(newUrl.String(), "application/json", bytes.NewReader(payload))
	if err != nil {
		return fmt.Errorf("failed to call train: [%w]", err)
	}
	if r.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected status code: [%d] %s", r.StatusCode, http.StatusText(r.StatusCode))
	}
	body, err := io.ReadAll(r.Body)
	if err != nil {
		return fmt.Errorf("failed to read train response: [%w]", err)
	}
	tr := sendManifestResponse{}
	err = json.Unmarshal(body, &tr)
	if err != nil {
		return fmt.Errorf("failed to unmarshal train response: [%w]", err)
	}

	slog.Debug("sent manifest", "id", transactionID)

	return nil
}
