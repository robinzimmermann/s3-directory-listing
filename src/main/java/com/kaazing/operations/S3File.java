package com.kaazing.operations;

import java.util.Date;

/**
 * Representation of a file from S3.
 */
public class S3File {

	/**
	 * The full path including the filename.
	 */
	private final String path;

	private final long size;
	
	private final Date lastModified;

	/**
	 * Just the filename portion of the overall path. name has the entire path.
	 */
	private final String filename;

	public S3File(String path, long size, Date lastModified) {
		this.path = path;
		this.size = size;
		this.lastModified = lastModified;

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
		return size;
	}

	public Date getLastModified() {
		return lastModified;
	}

	/**
	 * Get just the filename, without the parent path.
	 */
	public String getFilename() {
		return filename;
	}
}
