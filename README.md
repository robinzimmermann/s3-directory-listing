# s3-directory-listing

This project does do the following:

* Print a recursive directory listing of your entire bucket, or of a directory somewhere in your bucket. It also prints out the following metadata: file size, last modified date, cache-control value.


* Create a browsable directory index in an AWS S3 bucket from a given folder recursively

## Build

You'll need Java 1.8 and Maven installed.

```bash
$ mvn clean install
```

## Run

```bash
java -jar target/s3-directory-listing-VERSION.jar
```

This will show the usage. At a minimum, you need to supply your AWS key, AWS secret key, bucket name, and directory in the bucket that you want to make browseable.

If you're not sure what your AWS key and secret key are, then [read this](http://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSGettingStartedGuide/AWSCredentials.html).

### Recursive directory listing

This command will print out a recursive directory listing of the `public/releases` directory (and all of its sub-folders) from the `cdn.example.com` bucket:


```bash
java -jar target/s3-directory-listing-1.0-SNAPSHOT.jar
   --key XXXXXXXXXXXXXXXXXXXX
   --secret XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
   --bucket cdn.example.com
   --root public/releases
```

The `--root` parameter must be the top-level directory in the bucket. In the previous example, the `public` directory is in the root of the `cdn.example.com` bucket, and the `releases` directory is in the `public` directory.

A leading slash and/or trailing slash for the `--root` parameter are optional.

This command will print out a recursive directory listing of the entire bucket:

```bash
java -jar target/s3-directory-listing-1.0-SNAPSHOT.jar
   --key XXXXXXXXXXXXXXXXXXXX
   --secret XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
   --bucket cdn.example.com
   --root /
```

So will this:

```bash
java -jar target/s3-directory-listing-1.0-SNAPSHOT.jar
   --key XXXXXXXXXXXXXXXXXXXX
   --secret XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
   --bucket cdn.example.com
```

### Browsable index

You can also have the program upload index.html files to each folder that provides a directory listing. See [Kaazing releases](http://cdn.kaazing.com/releases/) for an example.

**WARNING: This will write over any index.html files you already have – in every folder you specify. Be careful!**

To create and upload the index files, add the `--index` parameter. This will put an `index.html` file in the `public/releases` directory, and all of its sub-directories:

```bash
java -jar target/s3-directory-listing-1.0-SNAPSHOT.jar
   --key XXXXXXXXXXXXXXXXXXXX
   --secret XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
   --bucket cdn.example.com
   --root public/releases
   --index
```

In addition, it will also put the following static resource files in the `--root` folder:

* index.css
* folder-icon.png
* folder-up-icon.png

**Note:** The static resource files, and the `index.html` files will not appear in the directory listing.

You can also specify the value of the `Cache-Control: max-age` directive of the `index.html` files, and of static resource files:

```bash
java -jar target/s3-directory-listing-1.0-SNAPSHOT.jar
   --key XXXXXXXXXXXXXXXXXXXX
   --secret XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
   --bucket cdn.example.com
   --root public/releases
   --index
   --max-age-index 60
   --max-age-resources 86400
```

This will set `cache-control: max-age=60` for the `index.html` files, and `cache-control: max-age=86400` for the static resource files. The values are in seconds, so 1 minute and 1 day, respectively, in the example above.

### Logging

The program will write a log to `s3-directory-listing.log` at the `INFO` level. You can change the log level with the `--log-level` parameter:

```bash
java -jar target/s3-directory-listing-1.0-SNAPSHOT.jar
   --key XXXXXXXXXXXXXXXXXXXX
   --secret XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
   --bucket cdn.example.com
   --root public/releases
   --index
   --max-age-index 60
   --max-age-resources 86400
   --log-level trace
```
