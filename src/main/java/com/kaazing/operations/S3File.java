package com.kaazing.operations;

import java.util.Date;

import com.amazonaws.services.s3.model.ObjectMetadata;

/**
 * Representation of a file from S3.
 */
public class S3File {

	/**
	 * The full path including the filename.
	 */
	private final String path;

	/**
	 * Just the filename portion of the overall path. name has the entire path.
	 */
	private final String filename;

	private final ObjectMetadata metadata;

	public S3File(String path, ObjectMetadata metadata) {
		this.path = path;
		this.metadata = metadata;

		int secondLastSlash = path.lastIndexOf('/', path.length() - 2);
		filename = path.substring(secondLastSlash + 1, path.length());
	}

	/**
	 * Get the full path of the file, including the filename.
	 */
	public String getPath() {
		return path;
	}

	public long getSize() {
		return metadata.getContentLength();
	}

	public Date getLastModified() {
		return metadata.getLastModified();
	}

	public String getCacheControl() {
		return metadata.getCacheControl();
	}
	
	public String getContentType() {
		return metadata.getContentType();
	}

	/**
	 * Get just the filename, without the parent path.
	 */
	public String getFilename() {
		return filename;
	}

	/**
	 * Convert a number into a human readable byte count. e.g. 1024 into 1 kB.
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
