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
	trainURL        = "http://localhost:8084/"
	defaultNumFiles = 500
	defaultFileSize = 5
	setupBatchSize  = 100
)

func main() {
	lh := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})
	slog.SetDefault(slog.New(lh))
	slog.Info("starting")

	startTime := time.Now()

	flagSo := flag.Bool("so", false, "only run setup stage")
	flagId := flag.String("id", "", "use existing test directory id (skips setup)")
	flagDebug := flag.Bool("debug", false, "debug mode")
	flagFileSize := flag.Int("fsize", defaultFileSize, "file size in kb")
	flagSetupFiles := flag.Int("sf", defaultNumFiles, "number of files to create on setup")
	flagCopyFiles := flag.Int("cp", defaultNumFiles, "number of files to copy in manifest")
	flagPublishFiles := flag.Int("pf", defaultNumFiles, "number of files to publish in manifest")
	flagVersion := flag.Int("v", 1, "version to create on publish")
	flagNoCleanup := flag.Bool("noclean", false, "do not clean up test files")
	flagCleanAll := flag.Bool("clean", false, "clean whole test directory and exit")

	flag.Parse()

	config := runConfig{
		CleanOnly:    *flagCleanAll,
		SetupOnly:    *flagSo,
		ID:           *flagId,
		FileSize:     *flagFileSize * 1024,
		SetupFiles:   *flagSetupFiles,
		CopyFiles:    *flagCopyFiles,
		PublishFiles: *flagPublishFiles,
		Version:      *flagVersion,
		Cleanup:      !(*flagNoCleanup),
	}

	if *flagDebug {
		logHandler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelDebug})
		slog.SetDefault(slog.New(logHandler))
		slog.Debug("debug mode")
	}

	slog.Debug("parsed flags", "flags ", flag.Args(), "config", config)

	err := run(config)
	if err != nil {
		slog.Error("fatal error", "err", err.Error())
		os.Exit(1)
	}

	endTime := time.Now()
	slog.Info("complete", "runTime", endTime.Sub(startTime).Round(time.Millisecond).Milliseconds())
}

type runConfig struct {
	CleanOnly    bool
	SetupOnly    bool
	ID           string
	FileSize     int
	NumFiles     int
	SetupFiles   int
	CopyFiles    int
	PublishFiles int
	Version      int
	Cleanup      bool
}

func run(config runConfig) error {
	if config.CleanOnly {
		slog.Info("cleaning entire test directory")
		return cleanup("/test")
	}

	testId := config.ID
	if config.SetupOnly || testId == "" {
		newTestId, err := initTestDir(defaultNumFiles, config.FileSize)
		if err != nil {
			return err
		}
		testId = newTestId
		slog.Info("initialised test directory", "testId", testId)
	}
	if config.SetupOnly {
		return nil
	}

	err := publishingSimulation(testId, config.CopyFiles, config.Version, config.PublishFiles, config.FileSize)
	if err != nil {
		return err
	}

	if config.Cleanup {
		cleanup(filepath.Join("/test", testId))
	}
	return nil
}

func initTestDir(numFiles, fileSize int) (string, error) {
	var testId, testDir string
	mu := sync.Mutex{}
	mu.Lock()
	wg := sync.WaitGroup{}
	var anyError error = nil
	slog.Info("initialising test directory", "numFiles", numFiles, "fileSize", fileSize)
	for sn := 0; sn < numFiles; sn += setupBatchSize {
		wg.Add(1)
		go func() {
			defer wg.Done()
			transactionID, err := openTransaction()
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

			for i := sn; i < numFiles && i < sn+setupBatchSize; i++ {
				subdir := genSubDirName(i)

				data, err := genFileContent(path.Join(testDir, subdir), subdir, fileSize)
				if err != nil {
					anyError = fmt.Errorf("failed to generate test data: %w", err)
				}
				slog.Debug("generated test data", "subdir", subdir, "length", len(data))

				uri := path.Join(testDir, subdir, "data.json")
				err = sendFile(transactionID, uri, data)
				if err != nil {
					slog.Error("failed to send file", "err", err.Error())
					anyError = err
				}
				slog.Debug("sent test data", "subdir", subdir, "length", len(data))
				if i%100 == 99 {
					slog.Debug("sent another hundred files", "total", i+1)
				}
			}

			err = commitTransaction(transactionID)
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
	return testId, nil
}

func publishingSimulation(testId string, subDirs, version, publishFiles, fileSize int) error {
	testDir := "/test/" + testId

	transactionID, err := openTransaction()
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
	for i := 0; i < subDirs; i++ {
		subdir := genSubDirName(i)
		mf.FilesToCopy = append(mf.FilesToCopy, fileToCopy{
			Source: path.Join(testDir, subdir, "data.json"),
			Target: path.Join(testDir, subdir, fmt.Sprintf("v%d", version), "data.json"),
		})
	}

	slog.Debug("created publishing manifest", "mf", mf)

	err = sendManifest(transactionID, &mf)
	if err != nil {
		slog.Error("failed to send manifest", "err", err.Error())
		return err
	}

	// Publish some new files

	for i := 0; i < publishFiles; i++ {
		subdir := genSubDirName(i)

		data, err := genFileContent(path.Join(testDir, subdir), subdir, fileSize)
		if err != nil {
			return fmt.Errorf("failed to generate test data for file publish: %w", err)
		}
		slog.Debug("generated publishing test data", "subdir", subdir, "length", len(data))

		uri := path.Join(testDir, subdir, "data.json")
		err = sendFile(transactionID, uri, data)
		if err != nil {
			slog.Error("failed to publish file", "err", err.Error())
			return err
		}
		slog.Debug("published test data", "subdir", subdir, "length", len(data))
		if i%100 == 99 {
			slog.Debug("published another hundred files", "total", i+1)
		}
	}

	err = commitTransaction(transactionID)
	if err != nil {
		slog.Error("failed to commit transaction", "err", err.Error())
		return err
	}
	slog.Info("publishing transaction committed", "id", transactionID)

	return nil
}

func cleanup(dirToClean string) error {
	transactionID, err := openTransaction()
	if err != nil {
		slog.Error("failed to open cleanup transaction", "err", err.Error())
		return err
	}
	slog.Info("cleanup transaction created", "id", transactionID)

	// Send a cleanup manifest of uri to be deleted
	mf := manifest{
		FilesToCopy:  make([]fileToCopy, 0),
		UrisToDelete: []string{dirToClean},
	}

	err = sendManifest(transactionID, &mf)
	if err != nil {
		slog.Error("failed to send cleanup manifest", "err", err.Error())
		return err
	}

	err = commitTransaction(transactionID)
	if err != nil {
		slog.Error("failed to commit cleanup transaction", "err", err.Error())
		return err
	}
	slog.Info("cleanup completed", "id", transactionID)
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

func openTransaction() (string, error) {
	url, err := url.Parse(trainURL)
	if err != nil {
		return "", fmt.Errorf("failed to parse train url: [%w]", err)
	}
	url.Path = path.Join(url.Path, "begin")
	slog.Debug("opening transaction", "url", url.String())

	r, err := http.Post(url.String(), "", http.NoBody)
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

func commitTransaction(transactionID string) error {
	url, err := url.Parse(trainURL)
	if err != nil {
		return fmt.Errorf("failed to parse train url: [%w]", err)
	}
	url.Path = path.Join(url.Path, "commit")
	q := url.Query()
	q.Set("transactionId", transactionID)
	url.RawQuery = q.Encode()

	slog.Debug("committing transaction", "url", url.String())

	r, err := http.Post(url.String(), "", http.NoBody)
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

func sendFile(transactionID, uri string, data []byte) error {
	url, err := url.Parse(trainURL)
	if err != nil {
		return fmt.Errorf("failed to parse train url: [%w]", err)
	}
	url.Path = path.Join(url.Path, "publish")
	q := url.Query()
	q.Set("transactionId", transactionID)
	q.Set("uri", uri)
	url.RawQuery = q.Encode()

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

	slog.Debug("sending file", "url", url.String())

	r, err := http.Post(url.String(), contentType, buf)
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

func sendManifest(transactionID string, mf *manifest) error {
	url, err := url.Parse(trainURL)
	if err != nil {
		return fmt.Errorf("failed to parse train url: [%w]", err)
	}
	url.Path = path.Join(url.Path, "CommitManifest")
	q := url.Query()
	q.Set("transactionId", transactionID)
	url.RawQuery = q.Encode()

	payload, err := json.Marshal(mf)
	if err != nil {
		return fmt.Errorf("failed to marshal manifest: [%w]", err)
	}

	slog.Debug("sending manifest", "url", url.String())
	r, err := http.Post(url.String(), "application/json", bytes.NewReader(payload))
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
