package com.kaazing.operations;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3ObjectSummary;

/**
 * Create a browsable directory listing in an AWS S3 bucket from a given folder recursively.
 */
public class S3DirectoryListing {

	private String key;
	private String secret;

	private String bucket;
	private String rootFolder;

	private String indexFilename = "index.html";
	private String cssFilename = "index.css";
	private String folderIconFilename = "folder-icon.png";
	private String folderUpIconFilename = "folder-up-icon.png";

	private Level logLevel = Level.INFO;

	/**
	 * Cache-Control: max-age directive for the index.html files. In seconds.
	 */
	private long htmlMaxAge = 2;

	/**
	 * Cache-Control: max-age directive for the static resource files. e.g. CSS file, images, etc. In seconds.
	 */
	private long resourcesMaxAge = 9;

	private final Logger logger = Logger.getLogger(S3DirectoryListing.class);

	private AmazonS3 s3client;

	private TreeMap<String, S3Folder> folders = new TreeMap<String, S3Folder>();

	public static void main(String[] args) {
		new S3DirectoryListing(args);
	}

	public S3DirectoryListing(String[] args) {
		if (!parseCommandLine(args)) {
			return;
		}
		LogManager.getRootLogger().setLevel(logLevel);
		readS3RootFolder();
		generateIndexFiles();
		uploadResourceFiles();
	}

	private boolean parseCommandLine(String[] args) {

		CommandLineParser parser = new DefaultParser();

		Options options = new Options();
		options.addOption("k", "key", true, "AWS access key");
		options.addOption("s", "secret", true, "AWS secret access key");
		options.addOption("b", "bucket", true, "AWS S3 bucket name");
		options.addOption("r", "root", true, "AWS S3 key that serves as the root directory");
		options.addOption("h", "max-age-html", true,
				"The Cache-Control: max-age value for the index.html files (in seconds). Default is " + htmlMaxAge);
		options.addOption("e", "max-age-resources", true,
				"The Cache-Control: max-age value for static files (in seconds). Default is " + resourcesMaxAge
						+ "\ne.g. CSS files, images, etc");
		options.addOption("l", "log-level", true, "Logging level: fatal, error, warn, info (default), debug, trace");
		options.addOption("?", "help", false, "Show usage help");

		try {
			// parse the command line arguments
			CommandLine line = parser.parse(options, args);

			if (line.hasOption("help") || line.hasOption("?")) {
				showUsage(options);
				return false;
			}

			if (line.hasOption("key")) {
				key = line.getOptionValue("key").trim();
			}

			if (line.hasOption("secret")) {
				secret = line.getOptionValue("secret").trim();
			}

			if (line.hasOption("bucket")) {
				bucket = line.getOptionValue("bucket").trim();
			}

			if (line.hasOption("root")) {
				rootFolder = line.getOptionValue("root").trim();
				if (rootFolder.charAt(rootFolder.length() - 1) != '/') {
					// This program expects the bucket name to have a trailing slash.
					rootFolder += "/";
				}
			}

			if (line.hasOption("max-age-html")) {
				try {
					htmlMaxAge = Long.valueOf(line.getOptionValue("max-age-html").trim());
				} catch (NumberFormatException e) {
					logger.info(String.format("You specified an invalid value for max-age-html. Using default of %d", htmlMaxAge));
				}
			}

			if (line.hasOption("max-age-resources")) {
				try {
					resourcesMaxAge = Long.valueOf(line.getOptionValue("max-age-resources").trim());
				} catch (NumberFormatException e) {
					logger.info(String.format("You specified an invalid value for max-age-resources. Using default of %d",
							resourcesMaxAge));
				}
			}

			if (line.hasOption("log-level")) {
				switch (line.getOptionValue("log-level").toUpperCase()) {
				case "FATAL":
					logLevel = Level.INFO;
					break;
				case "ERROR":
					logLevel = Level.ERROR;
					break;
				case "WARN":
					logLevel = Level.WARN;
					break;
				case "INFO":
					logLevel = Level.INFO;
					break;
				case "DEBUG":
					logLevel = Level.DEBUG;
					break;
				case "TRACE":
					logLevel = Level.TRACE;
					break;
				default:
					logLevel = Level.INFO;
					logger.info("Unknown log level specified. Setting to INFO");
				}
			}

		} catch (ParseException exp) {
			logger.error(String.format("Unexpected exception: %s", exp.getMessage()));
			showUsage(options);
			return false;
		}

		// Make sure we've got all the parameters we need.

		if (key == null) {
			logger.error("You didn't supply a key!");
			showUsage(options);
			return false;
		}

		if (secret == null) {
			logger.error("You didn't supply a secret!");
			showUsage(options);
			return false;
		}

		if (bucket == null) {
			logger.error("You didn't supply a bucket!");
			showUsage(options);
			return false;
		}

		if (rootFolder == null) {
			logger.error("You didn't supply a root!");
			showUsage(options);
			return false;
		}

		return true;
	}

	private void showUsage(Options options) {
		HelpFormatter formatter = new HelpFormatter();
		formatter.setWidth(120);
		System.out.println("");
		formatter.printHelp("s3-directory-listing", options);
		System.out.println("");
		System.out.println("Example:");
		System.out.println("");
		System.out.println("  s3-directory-listing");
		System.out.println("      --key XXXXXXXXXXXXXXXXXXXX");
		System.out.println("      --secret ihx3nCJKCp+ubYX6u29wb70OraTJ6uiGaZYKCpER");
		System.out.println("      --bucket my.bucket.com");
		System.out.println("      --root public/releases");
	}

	/**
	 * Connect to S3, read the root folder and all of its sub-folders, and build up a data structure of all the folders and file
	 * details.
	 */
	public void readS3RootFolder() {

		final BasicAWSCredentials awsCreds = new BasicAWSCredentials(key, secret);
		s3client = new AmazonS3Client(awsCreds);

		// Certain files will get stored in the root folder, such as the CSS file and icon images. They should not appear in the
		// directory listing and will be excluded.
		final HashMap<String, String> rootExcludeList = new HashMap<String, String>();

		rootExcludeList.put(indexFilename, indexFilename);
		rootExcludeList.put(cssFilename, cssFilename);
		rootExcludeList.put(folderIconFilename, folderIconFilename);
		rootExcludeList.put(folderUpIconFilename, folderUpIconFilename);

		logger.info(String.format("Reading %s/%s%n", bucket, rootFolder));

		try {
			final ListObjectsV2Request req = new ListObjectsV2Request().withBucketName(bucket).withPrefix(rootFolder)
					.withMaxKeys(10);
			ListObjectsV2Result result;
			do {
				result = s3client.listObjectsV2(req);

				for (S3ObjectSummary objectSummary : result.getObjectSummaries()) {

					// Is this key a folder or file?
					if (objectSummary.getKey().substring(objectSummary.getKey().length() - 1).equals("/")) {
						S3Folder folder = addFolder(objectSummary.getKey());
						logger.info(String.format("Adding folder %s", folder.getPath()));
					} else {
						S3File file = new S3File(objectSummary.getKey(), objectSummary.getSize(), objectSummary.getLastModified());
						logger.info(String.format("Adding file   %s", file.getPath()));
						// Extra the folder name holding this file
						int pos = objectSummary.getKey().lastIndexOf('/');
						String folderName = objectSummary.getKey().substring(0, pos + 1);
						S3Folder folder = addFolder(folderName);
						if (folderName.equals(rootFolder)) {
							// Don't add excluded files
							if (rootExcludeList.containsKey(file.getFilename())) {
								logger.trace(String.format("Excluding %s", file.getFilename()));
								continue;
							}
						}
						folder.addFile(file);
					}

				}
				// The S3 API returns paginated results. So keep looping through each page until we're done.
				req.setContinuationToken(result.getNextContinuationToken());
			} while (result.isTruncated() == true);

		} catch (AmazonServiceException ase) {
			logger.info("Caught an AmazonServiceException, " + "which means your request made it "
					+ "to Amazon S3, but was rejected with an error response " + "for some reason.");
			logger.info("Error Message:    " + ase.getMessage());
			logger.info("HTTP Status Code: " + ase.getStatusCode());
			logger.info("AWS Error Code:   " + ase.getErrorCode());
			logger.info("Error Type:       " + ase.getErrorType());
			logger.info("Request ID:       " + ase.getRequestId());
		} catch (AmazonClientException ace) {
			logger.info("Caught an AmazonClientException, " + "which means the client encountered "
					+ "an internal error while trying to communicate" + " with S3, "
					+ "such as not being able to access the network.");
			logger.info("Error Message: " + ace.getMessage());
		}

	}

	/**
	 * Add a folder and recursively add the parents if they don't exist.
	 */
	private S3Folder addFolder(String folderName) {
		logger.trace(String.format("Attemping to add folder %s to folder list", folderName));
		S3Folder folder = folders.get(folderName);
		if (folder == null) {
			logger.trace(String.format("%s does not exist in list, adding it", folderName));
			folder = new S3Folder(folderName);
			folders.put(folderName, folder);
		} else {
			logger.trace(String.format("%s already in list", folderName));
		}

		int secondLastSlash = folderName.lastIndexOf('/', folderName.length() - 2);
		if (secondLastSlash == -1) {
			logger.trace(String.format("%s has no parent, must be the root", folderName));
			return folder;
		}
		String parentName = folderName.substring(0, secondLastSlash + 1);
		logger.trace(String.format("%s has parent folder, %s, adding it to list", folderName, parentName));
		S3Folder parent = addFolder(parentName);
		parent.addFolder(folder);
		return folder;
	}

	/**
	 * Loop over all of the folders collected from S3 and add an index.hmtl file to each one.
	 */
	private void generateIndexFiles() {
		logger.info("");
		for (Entry<String, S3Folder> entry : folders.entrySet()) {
			S3Folder folder = entry.getValue();
			logger.info(String.format("Generating index file for %s", folder.getPath()));
			uploadIndexFile(folder);
		}
	}

	/**
	 * Generate and upload the index.html file for the given folder.
	 */
	private void uploadIndexFile(S3Folder folder) {
		try {

			String indexFile = createIndexFile(folder);

			byte[] bytes = indexFile.toString().getBytes(StandardCharsets.UTF_8);
			InputStream is = new ByteArrayInputStream(bytes);
			ObjectMetadata om = new ObjectMetadata();
			om.setContentType("text/html");
			om.setContentLength(bytes.length);
			om.setCacheControl("max-age=" + htmlMaxAge);
			String keyname = folder.getPath() + indexFilename;
			PutObjectRequest request = new PutObjectRequest(bucket, keyname, is, om);
			s3client.putObject(request);
		} catch (AmazonServiceException ase) {
			logger.info("Caught an AmazonServiceException, which " + "means your request made it "
					+ "to Amazon S3, but was rejected with an error response" + " for some reason.");
			logger.info("Error Message:    " + ase.getMessage());
			logger.info("HTTP Status Code: " + ase.getStatusCode());
			logger.info("AWS Error Code:   " + ase.getErrorCode());
			logger.info("Error Type:       " + ase.getErrorType());
			logger.info("Request ID:       " + ase.getRequestId());
		} catch (AmazonClientException ace) {
			logger.info("Caught an AmazonClientException, which " + "means the client encountered "
					+ "an internal error while trying to " + "communicate with S3, "
					+ "such as not being able to access the network.");
			logger.info("Error Message: " + ace.getMessage());
		}
	}

	/**
	 * Create a String representation of the index.html for the given folder.
	 */
	private String createIndexFile(S3Folder folder) {
		StringBuffer sb = new StringBuffer(500);
		sb.append("<!DOCTYPE html>");
		sb.append("<html lang=\"en\">");
		sb.append("");
		sb.append("<head>");
		sb.append("  <meta charset=\"utf-8\">");
		sb.append("  <link rel=\"shortcut icon\" type=\"image/png\" href=\"//kaazing.com/static/images/favicon-kaazing.png\">");
		sb.append("  <title>Kaazing Releases</title>");
		sb.append("  <link rel=\"stylesheet\" href=\"//cdn.kaazing.com/releases/index.css\">");
		sb.append("</head>");
		sb.append("");
		sb.append("<body>");
		sb.append("");

		sb.append(String.format("<h1>%s</h1>", folder.getPath()));

		sb.append(String.format(""));
		sb.append(String.format("<table id=\"list\">"));
		sb.append(String.format("  <thead>"));
		sb.append(String.format("    <tr>"));
		sb.append(String.format("      <th class=\"icon\"></th>"));
		sb.append(String.format("      <th class=\"name\">Name</th>"));
		sb.append(String.format("      <th class=\"size\" colspan=\"2\">Size</th>"));
		sb.append(String.format("      <th class=\"last-modified\">Last modified</th>"));
		sb.append(String.format("    </tr>"));
		sb.append(String.format("  </thead>"));
		sb.append(String.format("  <tbody>"));

		// Let users navigate up to the parent folder, but not past the root.
		if (!folder.getPath().equals(rootFolder)) {
			sb.append(String.format("    <tr>"));
			sb.append(String
					.format("      <td class=\"icon\"><a href=\"..\"><img src=\"//cdn.kaazing.com/releases/"+folderUpIconFilename+"\"></a></td>"));
			sb.append(String.format("      <td class=\"name\"><a href=\"..\">Parent Directory</a></td>"));
			sb.append(String.format("      <td class=\"size\"></td>"));
			sb.append(String.format("      <td class=\"size-units\"></td>"));
			sb.append(String.format("      <td class=\"last-modified\"></td>"));
			sb.append(String.format("    </tr>"));
		}

		// Show folders first.
		for (Entry<String, S3Folder> folderEntry : folder.getFolders().entrySet()) {
			S3Folder childFolder = folderEntry.getValue();
			String childFolderName = childFolder.getPath();
			int secondLastSlash = childFolderName.lastIndexOf('/', childFolderName.length() - 2);
			String childFolderEnding = childFolderName.substring(secondLastSlash + 1, childFolderName.length() - 1);

			sb.append(String.format("    <tr>"));
			sb.append(String.format(
					"      <td class=\"icon\"><a href=\"%s\"><img src=\"//cdn.kaazing.com/releases/"+folderIconFilename+"\"></a></td>",
					childFolderEnding));
			sb.append(String.format("      <td class=\"name\"><a href=\"%s\">%s</a></td>", childFolderEnding, childFolderEnding));
			sb.append(String.format("      <td class=\"size\"></td>"));
			sb.append(String.format("      <td class=\"size-units\"></td>"));
			sb.append(String.format("      <td class=\"last-modified\"></td>"));
			sb.append(String.format("    </tr>"));
		}

		// List files next.
		for (Entry<String, S3File> fileEntry : folder.getFiles().entrySet()) {
			S3File file = fileEntry.getValue();
			String size = humanReadableByteCount(file.getSize(), true);
			int spacePos = size.indexOf(' ');
			String sizeUnits = size.substring(spacePos + 1, size.length());
			size = size.substring(0, spacePos);
			sb.append(String.format("    <tr>"));
			sb.append(String.format("      <td class=\"icon\"></td>"));
			sb.append(String.format("      <td class=\"name\"><a href=\"%s\">%s</a></td>", file.getFilename(), file.getFilename()));
			sb.append(String.format("      <td class=\"size\">%s</td>", size));
			sb.append(String.format("      <td class=\"size-units\">%s</td>", sizeUnits));
			sb.append(String.format("      <td class=\"last-modified\">%s</td>", file.getLastModified()));
			sb.append(String.format("    </tr>"));
		}

		sb.append(String.format("  </tbody>"));
		sb.append(String.format("</table>"));
		sb.append("");
		sb.append("</body>");
		sb.append("");
		sb.append("</html>");
		return sb.toString();
	}

	/**
	 * Upload the static files into the root folder. Static files are the CSS file, images, etc.
	 */
	private void uploadResourceFiles() {
		logger.info("");
		ClassLoader classLoader = getClass().getClassLoader();

		File cssfile = new File(classLoader.getResource(cssFilename).getFile());
		uploadFile(cssfile, "text/css", resourcesMaxAge);

		File folderIconFile = new File(classLoader.getResource(folderIconFilename).getFile());
		uploadFile(folderIconFile, "text/png", resourcesMaxAge);

		File folderUpIconFile = new File(classLoader.getResource(folderUpIconFilename).getFile());
		uploadFile(folderUpIconFile, "text/png", resourcesMaxAge);
	}

	/**
	 * Generic method to upload a file to S3.
	 */
	private void uploadFile(File file, String contentType, long maxAge) {
		try {
			String destPath = rootFolder + file.getName();
			PutObjectRequest request = new PutObjectRequest(bucket, destPath, file);
			logger.info(String.format("Uploading %s", destPath));
			ObjectMetadata om = new ObjectMetadata();
			om.setContentLength(file.length());
			om.setContentType(contentType);
			if (maxAge >= 0) {
				om.setCacheControl("max-age=" + maxAge);
			}
			request.setMetadata(om);
			s3client.putObject(request.withMetadata(om));
		} catch (AmazonServiceException ase) {
			logger.info("Caught an AmazonServiceException, which " + "means your request made it "
					+ "to Amazon S3, but was rejected with an error response" + " for some reason.");
			logger.info("Error Message:    " + ase.getMessage());
			logger.info("HTTP Status Code: " + ase.getStatusCode());
			logger.info("AWS Error Code:   " + ase.getErrorCode());
			logger.info("Error Type:       " + ase.getErrorType());
			logger.info("Request ID:       " + ase.getRequestId());
		} catch (AmazonClientException ace) {
			logger.info("Caught an AmazonClientException, which " + "means the client encountered "
					+ "an internal error while trying to " + "communicate with S3, "
					+ "such as not being able to access the network.");
			logger.info("Error Message: " + ace.getMessage());
		}
	}

	/**
	 * Convert a number into a human redable byte count. e.g. 1024 into 1 kB.
	 * 
	 * Source: http://stackoverflow.com/questions/3758606/how-to-convert-byte-size-into-human-readable-format-in-java
	 * 
	 * @param bytes
	 *            the number to convert
	 * @param si
	 *            true for SI, false for binary units (e.g. MiB vs MB)
	 */
	public static String humanReadableByteCount(long bytes, boolean si) {
		int unit = si ? 1000 : 1024;
		if (bytes < unit)
			return bytes + " B";
		int exp = (int) (Math.log(bytes) / Math.log(unit));
		String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
		return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
	}

}
