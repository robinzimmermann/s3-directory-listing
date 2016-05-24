package com.kaazing.operations;

import java.util.Map;
import java.util.TreeMap;

import org.apache.log4j.Logger;

/**
 * Representation of a folder from S3. Contains child folders and files.
 */
public class S3Folder {

	/**
	 * The full path.
	 */
	private final String path;

	/**
	 * Just the folder name, not including the parent path.
	 */
	private final String folderName;

	private final TreeMap<String, S3Folder> folders = new TreeMap<String, S3Folder>();
	private final TreeMap<String, S3File> files = new TreeMap<String, S3File>();

	public S3Folder(String path) {
		this.path = path;

		// Derive the folder name (i.e. without the full path). The root folder is a special case.
		int secondLastSlash = path.lastIndexOf('/', path.length() - 2);
		if (secondLastSlash == -1) {
			folderName = path;
		} else {
			folderName = path.substring(secondLastSlash + 1, path.length());
		}
	}

	/**
	 * Get the full path.
	 */
	public String getPath() {
		return path;
	}

	public void addFile(S3File file) {
		files.put(file.getPath(), file);
	}

	public Map<String, S3File> getFiles() {
		return files;
	}

	public void addFolder(S3Folder folder) {
		folders.put(folder.getPath(), folder);
	}

	public Map<String, S3Folder> getFolders() {
		return folders;
	}

	/**
	 * Get just the folder name itself, without the parent path
	 */
	public String getFolderName() {
		return folderName;
	}
}
