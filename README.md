# The Train

**NOTE: We are currently in the process of deprecating this service due to:**

- _Poor performance_
- _Maintainability issues_
- _Inability to scale effectively_
- _Limitations of the underlying HTTP framework_

***

The Train is the publishing tool for the ONS website. Once a publish is triggered (either manually or scheduled) the
train is responsible copying the new/updated content from the internal publishing system onto the public facing
website. The content is wrapped in a transaction.

The Train is a JSON API and does not have a user interface. The internal publishing system ([Florence][2]
and [Zebdee][1]) are clients of The-Train and are responsible for orchestrating the publishing process.

## Endpoints

Below is a list of the available endpoints with a brief description of what they do. For more detail on the API you
can check out the `swagger.yml` in the root of this project.

| Endpoint            | Method   | Description                                                                          |
|---------------------|----------|--------------------------------------------------------------------------------------| 
| **/begin**          | **POST** | Create a transation for this publish.                                                |
| **/commitManifest** | **POST** | Send the publishing manifest                                                         |
| **/publish**        | **POST** | Send a file to be published in this transaction (called one or more times)           |
| **/commit**         | **POST** | Once all the publish content has been sent begin moving the content onto the web box |
| **/rollback**       | **POST** | Attempt to revert the publish if something goes wrong                                |
| **/transaction**    | **GET**  | Get the requested transaction                                                        |

#### Pre-publish steps

For scheduled publishes we execute the _begin_ and _commitManifest_ steps slightly _ahead_ of the publish time as a
minor performance gain. When its time to execute the actual publish the transation exists so we only need to move the
content to web box. **As previously mentioned this service is earmarked for deprecation. There are some fundamental
issues and performance is one of them.**

## Getting started

### Prerequisites

- git
- Java 8
- Maven
- Your `$zebedee_root` environment variable has been set (see Zebedee read me).
- [jq][3] _(not required but it's super useful for any JSON app)_

#### Get the code

```
git clone git@github.com:ONSdigital/The-Train.git
```

### Build and Run

#### Configuration

| Env variable                  | Description                                                                                                                             |
|-------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------| 
| `WEBSITE`                     | The path to the public facing website content directory. For dev local this will be `<YOUR_CONTENT_DIR>/zebedee/master`                 |
| `TRANSACTION_STORE`           | The directory in which to create the publishing transaction files. For dev local this will be `<YOUR_CONTENT_DIR>/zebedee/transactions` |
| `PUBLISHING_THREAD_POOL_SIZE` | The size of the thread pool to create for the publishing tasks.                                                                         |
| `PORT`                        | The HTTP port to bind to                                                                                                                |
| `MAX_FILE_UPLOAD_SIZE_MB`     | The maximum size allowed for uploaded files (in bytes), `-1` equals unlimited                                                           |
| `MAX_REQUEST_SIZE_MB`         | The maximum size allowed for a multipart/form-data request (in bytes), `-1` equals unlimited                                            |
| `FILE_THRESHOLD_SIZE_MB`      | The file size in MB after which the file will be temporarily stored on disk. The default size is 0 bytes.                               |

See the [Zebedee ReadMe][1] for a guide on setting up your zebedee root path and content directory.

To start the service run:

 ```
 make debug
 ``` 

Once the app has started up you can give a quick sanity test by running:

```
curl -X POST http://localhost:8084/begin | jq '.'
```

If successful you should see a response similar to:

```text
  % Total    % Received % Xferd  Average Speed   Time    Time     Time  Current
                                 Dload  Upload   Total   Spent    Left  Speed
100   244  100   244    0     0   1382      0 --:--:-- --:--:-- --:--:--  1386
{
  "message": "New transaction created.",
  "error": false,
  "transaction": {
    "id": "7133fb0a6b4c7fd01970716eec169212ed8b3cc7450e851bb31398e2ef63c4cc",
    "status": "started",
    "startDate": "2018-10-19T11:22:40.532+0100",
    "uriInfos": [],
    "uriDeletes": [],
    "errors": []
  }
}
```

You should also have a directory name the same as the transaction ID in your response under your
configurated `transactions` directory. :tada:

## Testing

There is a [stress test tool](stress-test/README.md) that allows you to send multiple files and a manifest at an
instance of the train to simulate a high publishing load.

[1]: https://github.com/ONSdigital/zebedee

[2]: https://github.com/ONSdigital/florence

[3]: https://stedolan.github.io/jq/tutorial/

### Tools

To run some of our tests you will need additional tooling:

#### Audit

For Java auditing, we use mvn `ossindex:audit` which requires you to [setup an OSS Index account](https://github.com/ONSdigital/dp/blob/main/guides/MAC_SETUP.md#oss-index-account-and-configuration)
and make some updates to [Maven: Local Setup for ossindex:audit](https://github.com/ONSdigital/dp/blob/main/guides/MAC_SETUP.md#maven-local-setup-for-ossindexaudit)
