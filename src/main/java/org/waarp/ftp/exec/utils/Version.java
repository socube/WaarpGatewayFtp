// DO NOT MODIFY - WILL BE OVERWRITTEN DURING THE BUILD PROCESS
package org.waarp.ftp.exec.utils;

/** Provides the version information of Waarp FTP Exec. */
public final class Version {
	/** The version identifier. */
	public static final String ID = "2.0.9";

	/** Prints out the version identifier to stdout. */
	public static void main(String[] args) {
		System.out.println(ID);
	}

	private Version() {
		super();
	}
}