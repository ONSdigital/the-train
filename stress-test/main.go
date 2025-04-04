package main

import (
	"bytes"
	"encoding/json"
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
	"time"
)

const (
	trainURL        = "http://localhost:8084/"
	defaultSubDirs  = 100
	defaultFileSize = 256 * 1024
)

func main() {
	lh := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelDebug})
	slog.SetDefault(slog.New(lh))

	slog.Info("starting")

	err := run()
	if err != nil {
		slog.Error("fatal error", "err", err.Error())
		os.Exit(1)
	}

	slog.Info("complete")
}

func run() error {
	testId, err := initTestDir(defaultSubDirs, defaultFileSize)
	if err != nil {
		return err
	}
	slog.Info("initialised test directory", "testId", testId)
	return nil
}

func initTestDir(subDirs, filesize int) (string, error) {
	transactionID, err := openTransaction()
	if err != nil {
		slog.Error("failed to open transaction", "err", err.Error())
		return "", err
	}
	slog.Info("transaction created", "id", transactionID)

	// reuse the initial transactionId as a test dir
	testDir := "/test/" + transactionID

	for i := 0; i < subDirs; i++ {
		subdir := genSubDirName(i)

		data, err := genFileContent(path.Join(testDir, subdir), subdir, filesize)
		if err != nil {
			return "", fmt.Errorf("failed to generate test data: %w", err)
		}
		slog.Debug("generated test data", "subdir", subdir, "length", len(data))

		uri := path.Join(testDir, subdir, "data.json")
		err = sendFile(transactionID, uri, data)
		if err != nil {
			slog.Error("failed to send file", "err", err.Error())
			return "", err
		}
		slog.Debug("sent test data", "subdir", subdir, "length", len(data))
	}

	err = commitTransaction(transactionID)
	if err != nil {
		slog.Error("failed to commit transaction", "err", err.Error())
		return "", err
	}
	slog.Info("transaction committed")

	return transactionID, nil
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
		case "C":
			padding[i] = byte(rand.IntN(26) + 65)
			next = ""
		case " ":
			padding[i] = byte(' ')
			next = ""
		case ".":
			padding[i] = byte('.')
			next = "C"
		default:
			padding[i] = byte(rand.IntN(26) + 97)
		}
		chsLeft--
	}
	padding[size-1] = byte('.')
	return string(padding)
}
