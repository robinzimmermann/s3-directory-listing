# s3-directory-listing

Create a browsable directory listing in an AWS S3 bucket from a given folder recursively.

## Build

You'll need Java 1.8 and Maven installed.

```bash
$ mvn clean install
```

## Run

```bash
java -jar target/s3-directory-listing-VERSION.jar
```

This will show the usage. At a minimum, you need to supply your AWS key, secret key, bucket name, and directory in the bucket that you want to make browseable.

Example:

```bash
java -jar target/uber-s3-directory-listing-1.0-SNAPSHOT.jar --key XXXXXXXXXXXXXXXXXXXX --secret ihx3nCJKCp+ubYX6u29wb70OraTJ6uiGaZYKCpER --bucket cdn.kaazing.com --root releases
```

You can also specify the value of the `Cache-Control: max-age` directive of the index.html files, and of static resources like the CSS file, images, etc.