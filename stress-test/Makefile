APP=train-stress-test

ENV?=sandbox
HOST?=1
TRAIN_URL=http://localhost:8084/

GOOS?=$(shell go env GOOS)
GOARCH?=$(shell go env GOARCH)

BUILD=build
BUILD_ARCH=$(BUILD)/$(GOOS)-$(GOARCH)

.PHONY: build
build:
	go build -o $(BUILD_ARCH)/$(APP) -ldflags "-X 'main.DefaultTrainURL=$(TRAIN_URL)'"

.PHONY: build-aws
build-aws:
	make CGO_ENABLED=0 GOOS=linux GOARCH=amd64 TRAIN_URL=http://localhost:10100/ build

.PHONY: deploy
deploy: build-aws
	dp scp $(ENV) web_mount $(HOST)  -- $(BUILD)/linux-amd64/$(APP) $(APP);

.PHONY: clean
clean:
	[[ ! -d $(BUILD) ]] || rm -r $(BUILD)

