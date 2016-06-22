/* Copyright 2013 University of North Carolina at Chapel Hill.  All rights reserved. */
package abra;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileReader;
import htsjdk.samtools.ValidationStringency;
import htsjdk.samtools.SAMRecord;

/**
 * Reader class for SAM or BAM file containing paired reads.
 * Provides the ability to iterate over all mated pairs in the file.
 * Supports multiple mappings for the same read. (i.e. repeating read id's)
 * SAM versus BAM format is determined from the file suffix.  i.e. ".sam" or ".bam"
 *
 * @author Lisle E. Mose (lmose at unc dot edu)
 */
public class SimpleSamReadPairReader implements Iterable<ReadPair> {

	private SAMRecord cachedRead;
	private SAMRecord lastRead;
	private Iterator<SAMRecord> iter;
	private int lineCnt = 0;
	private SAMFileReader inputSam;

	public SimpleSamReadPairReader(String filename) {
		File inputFile = new File(filename);

		inputSam = new SAMFileReader(inputFile);
		inputSam.setValidationStringency(ValidationStringency.SILENT);

		iter = inputSam.iterator();
	}

	public void close() {
		inputSam.close();
	}

	public SAMFileHeader getHeader() {
		return inputSam.getFileHeader();
	}

	private ReadPair pairReads(List<SAMRecord> reads1, List<SAMRecord> reads2) {

		if ((reads1.size() > 1) || (reads2.size() > 1)) {
			String msg = "Warning: Multi-mapped reads not supported: " +
					(reads1.size() > 0 ? reads1.get(0).getReadName() : "null") + " - " +
					(reads2.size() > 0 ? reads2.get(0).getReadName() : "null");
			System.err.println(msg);
//			throw new RuntimeException(msg);
		}

		SAMRecord read1 = null;
		SAMRecord read2 = null;

		if (reads1.size() >= 1) {
			read1 = reads1.get(0);
		}

		if (reads2.size() >= 1) {
			read2 = reads2.get(0);
		}

		return new ReadPair(read1, read2);
	}

	private String getBaseName(SAMRecord read) {
		String baseName;

		int idx = read.getReadName().indexOf('/');
		if (idx > -1) {
			baseName = read.getReadName().substring(0, idx);
		} else {
			baseName = read.getReadName();
		}

		return baseName;
	}

	private ReadPair getNextReadPair() {

		return getReadPair();
	}

	private ReadPair getReadPair() {

		List<SAMRecord> reads1 = new ArrayList<SAMRecord>();
		List<SAMRecord> reads2 = new ArrayList<SAMRecord>();

		// Get the list of records for the first read
		SAMRecord read = getNextRead();
		if (read != null) {
			String baseName = getBaseName(read);

			while ((read != null) && (baseName.equals(getBaseName(read)))) {
				if ((read.getReadPairedFlag()) && (read.getFirstOfPairFlag())) {
					reads1.add(read);
				} else if ((read.getReadPairedFlag()) && (read.getSecondOfPairFlag())) {
					reads2.add(read);
				} else {
//					System.err.println("Unpaired read: " + read);
				}

				read = getNextRead();
			}

			// Put the last read (which isn't part of this sequence) back
			if (read != null) {
				unGetRead();
			}
		}

		return pairReads(reads1, reads2);
	}

	private boolean hasMoreReads() {
		return cachedRead != null || iter.hasNext();
	}

	private SAMRecord getNextRead() {
		SAMRecord next = null;

		if (cachedRead != null) {
			next = cachedRead;
			cachedRead = null;
		} else {
			if (iter.hasNext()) {
				next = iter.next();
				lineCnt++;
				if ((lineCnt % 1000000) == 0) {
					System.err.println("record: " + lineCnt);
				}
			} else {
				next = null;
			}
		}

		lastRead = next;

		return next;
	}

	private void unGetRead() {
		cachedRead = lastRead;
	}

	public Iterator<ReadPair> iterator() {
		return new ReadPairIterator(this);
	}

	private static class ReadPairIterator implements Iterator<ReadPair> {

		private SimpleSamReadPairReader reader;
		private ReadPair nextReadPair = null;

		ReadPairIterator(SimpleSamReadPairReader reader) {
			this.reader = reader;
		}

		@Override
		public boolean hasNext() {
			if ((nextReadPair == null) && (hasMoreReads())) {
				nextReadPair = reader.getNextReadPair();
			}

			return nextReadPair != null;
		}

		private boolean hasMoreReads() {
			return reader.hasMoreReads();
		}

		@Override
		public ReadPair next() {
			ReadPair pair = nextReadPair;
			nextReadPair = null;
			return pair;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException("Remove not supported for ReadPairIterator.");
		}
	}

	public static void main(String[] args) {
//		SamReadPairReader reader = new SamReadPairReader("/home/lisle/data/coord_convert/sorted_tiny.sam");
		String sam = args[0];
		SimpleSamReadPairReader reader = new SimpleSamReadPairReader(sam);
//		SimpleSamReadPairReader reader = new SimpleSamReadPairReader("/home/lmose/dev/abra/simplesam/candidates.bam");


		for (ReadPair pair : reader) {
			System.err.println(pair);
		}
	}
}
