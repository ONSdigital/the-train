# The-Train Stress Test Tool

## Overview

This tool sends test data to an instance of the train in order to evaluate performance under high load. Briefly the tool
works as follows…

- The tool starts with a setup stage where it opens transactions and uses them to set up a given number of files
  created from randomised content to the content directory. These will have a path of `/test/{test_id}/aaaa/data.json`
  where the `test_id` is randomly generated* and the `aaaa` is a sequential subdirectory of the form `aaaa`, `aaab`,
  `aaac`, … , `zzzz`. This is done in batches to overcome train performance limitations with large numbers of sent
  files.

  (* the test_id is actually the same ID as the first transaction used to send the test files)

- It then continues with a publishing step that simulates zebedee publishing by opening a new transaction and then…
    - Sending a manifest of files to copy (eg. `/test/{test_id}/aaaa/data.json` ->
      `/test/{test_id}/aaaa/v1/data.json` ),
      simulating pre-publish behaviour.
    - Sending a number of new files overwriting some of the files created during the setup stage or adding new ones.
    - Committing the transaction

- Finally, it cleans up after itself by deleting the `/test/{id}` directory unless the `-noclean` option is supplied.
  Note that this does not clean the parent `/test` directory which can instead by cleaned by using the `-clean` option
  instead.

## How to use

Build the command using the following which will create a binary in `build` (eg. `build/darwin-amd64/train-stress-test`
for MacOS).

```shell
make build
```

This can be run locally with the default options…

```shell
build/darwin-amd64/train-stress-test
```

The following command line arguments are available to modify the default settings.

| Flag       | Description                                          | Default                   | 
|------------|------------------------------------------------------|---------------------------|
| `-batch`   | number of files in each batch on setup               | `100`                     |
| `-clean`   | clean whole test directory and exit                  |                           |
| `-cp`      | number of files to copy via manifest                 | `500`                     |
| `-debug`   | debug mode                                           |                           |
| `-fsize`   | file size in kb                                      | `5`                       |
| `-id`      | string  use existing test directory id (skips setup) |                           |
| `-noclean` | do not clean up test files                           |                           |
| `-pf`      | number of files to send during publish               | `500`                     |
| `-sf`      | number of files to send on setup                     | `500`                     |
| `-so`      | only run setup stage                                 |                           |
| `-url`     | string  train url                                    | `http://localhost:10100/` |
| `-v`       | version to create on publish                         | `1`                       |

### Deploying and running on an environment

⚠️ **Warning**: **DO NOT run the tool in prod**. It creates test pages in the content directory which will be available
the public. Although the files contain a disclaimer that the test data should be disregarded this would be a bad thing
to do.

Build and deploy the tool to `sandbox web 1` using…

```shell
make deploy
```

You will then be able to ssh to the box and run the tool against its train using…

```shell
dp ssh sandbox web 1
```

```shell
./train-stress-test
```

To deploy to a different environment or host, use environment variables with make. For example to deploy to
`staging web 2` use…

```shell
ENV=staging HOST=2 make deploy
```

