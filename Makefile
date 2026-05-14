SHELL=bash

JAVA_OPTS=-Xmx1024m -Xms1024m -Xdebug -Xrunjdwp:transport=dt_socket,address=8004,server=y,suspend=n

OSSINDEX_ERRORS = "Unable to contact OSS Index|authentication failed|401 Unauthorized|403 Forbidden|429 Too Many Requests|Too many requests|Rate limit|Unknown host|Connection refused|timed out|unreachable|402 Payment Required"

## The default website content directory
WEBSITE_DEFAULT:=target/website

ifndef WEBSITE
$(warning WEBSITE env var not found applying default: $(WEBSITE_DEFAULT))
export WEBSITE = ${WEBSITE_DEFAULT}
endif

## The default publish transactions directory
TRANSACTIONS_DEFAULT:=target/transactions

ifndef TRANSACTION_STORE
$(warning TRANSACTION_STORE env var not found applying default: ${TRANSACTIONS_DEFAULT})
export TRANSACTION_STORE = ${TRANSACTIONS_DEFAULT}
endif

## The default bind address
PORT_DEFAULT:=8084

ifndef PORT
$(warning PORT env var not found applying default: $(PORT_DEFAULT))
export PORT = ${PORT_DEFAULT}
endif

## The default thread pool size
PUBLISHING_THREAD_POOL_SIZE_DEFAULT:=100

ifndef PUBLISHING_THREAD_POOL_SIZE
$(warning PUBLISHING_THREAD_POOL_SIZE env var not found applying default: ${PUBLISHING_THREAD_POOL_SIZE_DEFAULT})
export PUBLISHING_THREAD_POOL_SIZE = ${PUBLISHING_THREAD_POOL_SIZE_DEFAULT}
endif

## The default max file upload size in bytes (-1 == unlimited)
MAX_FILE_UPLOAD_SIZE_MB_DEFAULT:=-1

ifndef MAX_FILE_UPLOAD_SIZE_MB
$(warning MAX_FILE_UPLOAD_SIZE_MB env var not found applying default: ${MAX_FILE_UPLOAD_SIZE_MB_DEFAULT})
export MAX_FILE_UPLOAD_SIZE_MB = ${MAX_FILE_UPLOAD_SIZE_MB_DEFAULT}
endif

## The default max request size in bytes (-1 == unlimited)
MAX_REQUEST_SIZE_MB_DEFAULT:=-1

ifndef MAX_REQUEST_SIZE_MB
$(warning MAX_REQUEST_SIZE_MB env var not found applying default: ${MAX_REQUEST_SIZE_MB_DEFAULT})
export MAX_REQUEST_SIZE_MB = ${MAX_REQUEST_SIZE_MB_DEFAULT}
endif

## The file upload threshold - files uploading this size will be written to a temp file rather than being held in memory. Size in MB
FILE_THRESHOLD_SIZE_MB_DEFAULT:=10

ifndef FILE_THRESHOLD_SIZE_MB
$(warning FILE_THRESHOLD_SIZE_MB env var not found applying default: ${FILE_THRESHOLD_SIZE_MB_DEFAULT})
export FILE_THRESHOLD_SIZE_MB = ${FILE_THRESHOLD_SIZE_MB_DEFAULT}
endif

test:
	mvn -Dossindex.skip test
audit:
	@echo "🔍 Running OSS Index audit..." && \
	mkdir -p target && \
	mvn -B ossindex:audit > target/ossindex-audit.log 2>&1; status=$$?; cat target/ossindex-audit.log; \
	[ $$status -eq 0 ] && grep -Eiqn $(OSSINDEX_ERRORS) target/ossindex-audit.log && \
		{ echo "❌ OSS Index API/auth/network error detected — see target/ossindex-audit.log"; exit 1; }; \
	exit $$status
ensure_dirs:
	@if [[ $(WEBSITE) == $(WEBSITE_DEFAULT) ]]; then mkdir -p $(WEBSITE_DEFAULT); fi
	@if [[ $(TRANSACTION_STORE) == $(TRANSACTIONS_DEFAULT) ]]; then mkdir -p $(TRANSACTIONS_DEFAULT); fi
build:
	mvn -DskipTests -Dossindex.skip clean package
debug: build ensure_dirs
	 java ${JAVA_OPTS} -jar target/the-train-*.jar
.PHONY: build debug test audit ensure_dirs

