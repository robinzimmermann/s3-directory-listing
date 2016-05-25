package com.kaazing.operations;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
	private String rootFolder = ""; // This means the overall root directory: "/"

	private String indexFilename = "index.html";
	private String cssFilename = "index.css";
	private String folderIconFilename = "folder-icon.png";
	private String folderUpIconFilename = "folder-up-icon.png";

	private Level logLevel = Level.INFO;

	private boolean indexing = false;

	/**
	 * Cache-Control: max-age directive for the index.html files. In seconds.
	 */
	private long indexMaxAge = 2;

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

		if (indexing) {
			generateIndexFiles();
			uploadResourceFiles();
		} else {
			printDirectoryList(folders.get("/"));
		}

		logger.info("\nDone");
	}

	private boolean parseCommandLine(String[] args) {

		CommandLineParser parser = new DefaultParser();

		Options options = new Options();
		options.addOption("k", "key", true, "AWS access key");
		options.addOption("s", "secret", true, "AWS secret access key");
		options.addOption("b", "bucket", true, "AWS S3 bucket name");
		options.addOption("r", "root", true, "AWS S3 key that serves as the root directory. Default is /");
		options.addOption("m", "max-age-index", true,
				"The Cache-Control: max-age value for the index.html files (in seconds). Default is " + indexMaxAge
						+ ".\nIgnored if -i is not set");
		options.addOption("e", "max-age-resources", true,
				"The Cache-Control: max-age value for static CSS, image, etc files (in seconds). Default is " + resourcesMaxAge
						+ "\nIgnored if -i is not set.");
		options.addOption("l", "log-level", true, "Logging level: fatal, error, warn, info (default), debug, trace");
		options.addOption("i", "index", false,
				"Upload index files to make the S3 folders browsable\nWARNING: This will override existing index.html files in every directory!");
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
				if (rootFolder.equals("/")) {
					rootFolder = ""; // This is how to represent the / directory
				} else {
					// S3 expects the folder name to not have a starting slash.
					if (rootFolder.charAt(0) == '/') {
						rootFolder = rootFolder.substring(1);
					}
					// S3 expects the folder name to have a trailing slash.
					if (rootFolder.charAt(rootFolder.length() - 1) != '/') {
						rootFolder += "/";
					}
				}
			}

			if (line.hasOption("max-age-index")) {
				try {
					indexMaxAge = Long.valueOf(line.getOptionValue("max-age-index").trim());
				} catch (NumberFormatException e) {
					logger.info(String.format("You specified an invalid value for max-age-index. Using default of %d", indexMaxAge));
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

			if (line.hasOption("index")) {
				indexing = true;
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
		formatter.setWidth(132);
		System.out.println("");
		formatter.printHelp("s3-directory-listing", options);
		System.out.println("");
		System.out.println("Example:");
		System.out.println("");
		System.out.println("  s3-directory-listing");
		System.out.println("      --key XXXXXXXXXXXXXXXXXXXX");
		System.out.println("      --secret XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
		System.out.println("      --bucket cdn.example.com");
		System.out.println("      --root public/releases");
	}

	/**
	 * Connect to S3, read the root folder and all of its sub-folders, and build up a data structure of all the folders and file
	 * details.
	 */
	public void readS3RootFolder() {

		final BasicAWSCredentials awsCreds = new BasicAWSCredentials(key, secret);
		s3client = new AmazonS3Client(awsCreds);

		logger.info(String.format("Scanning %s/%s...", bucket, rootFolder));

		try {
			final ListObjectsV2Request req = new ListObjectsV2Request().withBucketName(bucket).withPrefix(rootFolder)
					.withMaxKeys(5);
			ListObjectsV2Result result;
			do {
				result = s3client.listObjectsV2(req);

				for (S3ObjectSummary objectSummary : result.getObjectSummaries()) {
					logger.debug(String.format("Found key: %s", objectSummary.getKey()));

					// Is this key a folder or file?
					if (objectSummary.getKey().substring(objectSummary.getKey().length() - 1).equals("/")) {
						S3Folder folder = addFolder(objectSummary.getKey());
						logger.trace(String.format("Reading folder: %s", folder.getPath()));
					} else {
						ObjectMetadata om = s3client.getObjectMetadata(bucket, objectSummary.getKey());
						S3File file = new S3File(objectSummary.getKey(), om);
						logger.trace(String.format("Reading file:   %s", file.getPath()));
						// Extract the folder name holding this file. Handle special case if the parent is the root.
						int pos = objectSummary.getKey().lastIndexOf('/');
						String folderName;
						if (pos == -1) {
							folderName = "/";
						} else {
							folderName = objectSummary.getKey().substring(0, pos + 1);
						}
						S3Folder folder = addFolder(folderName);
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

		// Special case, if this is /, then there is no need to proceed, which would be an
		// infinite recursion.
		if (folder.getPath().equals("/")) {
			return folder;
		}

		// Figure out the parent folder.

		int secondLastSlash = folderName.lastIndexOf('/', folderName.length() - 2);
		String parentName;
		if (secondLastSlash == -1) {
			logger.trace(String.format("%s has no parent, so the parent must be the root", folderName));
			parentName = "/";
			// return folder;
		} else {
			parentName = folderName.substring(0, secondLastSlash + 1);
		}
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
			if (folder.getPath().equals("/")) {
				// Root is a special case, ignore it
				continue;
			}
			logger.info(String.format("Uploading index file for %s", folder.getPath()));
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
			om.setCacheControl("max-age=" + indexMaxAge);
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
		sb.append("  <title>Directory Listing</title>");
		sb.append("  <link rel=\"stylesheet\" href=\"//" + bucket + "/" + rootFolder + "index.css\">");
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
			sb.append(String.format("      <td class=\"icon\"><a href=\"..\"><img src=\"//" + bucket + "/" + rootFolder
					+ folderUpIconFilename + "\"></a></td>"));
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
			sb.append(String.format("      <td class=\"icon\"><a href=\"%s\"><img src=\"//" + bucket + "/" + rootFolder
					+ folderIconFilename + "\"></a></td>", childFolderEnding));
			sb.append(String.format("      <td class=\"name\"><a href=\"%s\">%s</a></td>", childFolderEnding, childFolderEnding));
			sb.append(String.format("      <td class=\"size\"></td>"));
			sb.append(String.format("      <td class=\"size-units\"></td>"));
			sb.append(String.format("      <td class=\"last-modified\"></td>"));
			sb.append(String.format("    </tr>"));
		}

		// List files next.

		// Certain files will get stored in the root folder, such as the CSS file and icon images. They should not appear in the
		// directory listing and will be excluded. Also, the index.html file in each folder should not be displayed.
		final HashMap<String, String> rootExcludeList = new HashMap<String, String>();

		rootExcludeList.put(indexFilename, indexFilename);
		rootExcludeList.put(cssFilename, cssFilename);
		rootExcludeList.put(folderIconFilename, folderIconFilename);
		rootExcludeList.put(folderUpIconFilename, folderUpIconFilename);

		for (Entry<String, S3File> fileEntry : folder.getFiles().entrySet()) {

			S3File file = fileEntry.getValue();

			// Don't show excluded files
			if (rootExcludeList.containsKey(file.getFilename())) {
				logger.trace(String.format("Excluding %s", file.getFilename()));
				continue;
			}

			String size = S3File.humanReadableByteCount(file.getSize(), true);
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
		uploadResourceFile(cssFilename, "text/css", resourcesMaxAge);
		uploadResourceFile(folderIconFilename, "image/png", resourcesMaxAge);
		uploadResourceFile(folderUpIconFilename, "image/png", resourcesMaxAge);
	}

	/**
	 * Generic-ish method to upload a resource file to S3.
	 */
	private void uploadResourceFile(String filename, String contentType, long maxAge) {
		try {

			String keyname = rootFolder + filename;

			logger.info(String.format("Uploading %s", keyname));

			InputStream is = getClass().getClassLoader().getResourceAsStream(filename);

			ObjectMetadata om = new ObjectMetadata();
			om.setContentType(contentType);
			om.setContentLength(is.available());
			if (maxAge >= 0) {
				om.setCacheControl("max-age=" + maxAge);
			}
			
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
		} catch (IOException e) {
			e.printStackTrace();
			logger.error(String.format("Error reading %s", filename), e);
		}
	}

	/**
	 * Write out the directory structure, starting from the given root.
	 */
	private void printDirectoryList(S3Folder root) {
		logger.info("Directory Listing");
		logger.info("Name, size, last modified, cache-control, content-type");
		logger.info("----------------------------------------");
		logger.info(String.format("%s", root.getPath()));
		printDirectoryList(root, 1);
	}

	private void printDirectoryList(S3Folder folder, int level) {
		String padding = String.format("%1$" + (level * 2) + "s", " ");
		// List folders first.
		for (Entry<String, S3Folder> folderEntry : folder.getFolders().entrySet()) {
			S3Folder childFolder = folderEntry.getValue();
			String childFolderPath = childFolder.getPath();
			logger.info(String.format("%s%s", padding, childFolderPath));
			printDirectoryList(childFolder, level + 1);
		}
		// List files second.
		for (Entry<String, S3File> fileEntry : folder.getFiles().entrySet()) {
			S3File file = fileEntry.getValue();
			logger.info(String.format("%s%s, %s, %s, %s, %s", padding, file.getPath(),
					S3File.humanReadableByteCount(file.getSize(), true), file.getLastModified(), file.getContentType(), file.getCacheControl()));
		}
	}

}
