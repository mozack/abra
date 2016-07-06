/* Copyright 2013 University of North Carolina at Chapel Hill.  All rights reserved. */
package abra;

import htsjdk.samtools.SAMRecord;

// TODO: Rename?
public class ReadPosition {

	private SAMRecord read;
	private int position;
	private int numMismatches;

	public ReadPosition(SAMRecord read, int position, int numMismatches) {
		this.read = read;
		this.position = position;
		this.numMismatches = numMismatches;
	}

	public SAMRecord getRead() {
		return read;
	}

	public int getPosition() {
		return position;
	}

	public int getNumMismatches() {
		return numMismatches;
	}

	public void setRead(SAMRecord read) {
		this.read = read;
	}
}
