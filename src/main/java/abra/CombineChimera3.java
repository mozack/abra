/* Copyright 2013 University of North Carolina at Chapel Hill.  All rights reserved. */
package abra;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import htsjdk.samtools.Cigar;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileWriter;
import htsjdk.samtools.SAMFileWriterFactory;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMFileHeader.SortOrder;

/**
 * Combines chimeric alignments caused by indels when appropriate
 *
 * @author Lisle E. Mose (lmose at unc dot edu)
 */
public class CombineChimera3 {

	// TODO: Revisit this requirement. Consider removing when
	// introducing dynamic complex region identification
	public static final int MAX_GAP_LENGTH = 2000;

	// Minimum number of non clipped bases that must be between an identified indel and
	// the beginning/end of the contig
	int minIndelBuffer;

	public void combine(String input, String output, int minIndelBuffer, CompareToReference2 c2r) {
		IndelShifter indelShifter = new IndelShifter();

		this.minIndelBuffer = minIndelBuffer;
		SamMultiMappingReader reader = new SamMultiMappingReader(input);

		SAMFileHeader header = reader.getFileHeader();
		header.setSortOrder(SortOrder.unsorted);

		SAMFileWriter outputReadsBam = new SAMFileWriterFactory().makeSAMOrBAMWriter(
				header, true, new File(output));

		for (List<SAMRecord> readList : reader) {
			List<SAMRecord> processedReads = processRead(readList);

			for (SAMRecord read : processedReads) {
				outputReadsBam.addAlignment(indelShifter.shiftIndelsLeft(read, c2r));
			}
		}

		outputReadsBam.close();
		reader.close();

		System.err.println("Done combining chimeric reads.");
	}

	// SAMRecords in the input represent chimera for the same read
	protected List<SAMRecord> processRead(List<SAMRecord> readList) {
		List<SAMRecord> reads = null;
		boolean isCombined = false;

		for (SAMRecord read : readList) {
			read.clearAttributes();
			SAMRecordUtils.replaceHardClips(read);
		}

		sortReadsByPosition(readList);

		pruneLikelyInserts(readList);

		if (readList.size() == 2) {
			SAMRecord read1 = readList.get(0);
			SAMRecord read2 = readList.get(1);

			// Look for same reference, strand and multipart Cigar
			if ((read1.getReferenceName().equals(read2.getReferenceName())) &&
				(read1.getReadNegativeStrandFlag() == read2.getReadNegativeStrandFlag()) &&
				(read1.getCigarLength() >= 2) &&
				(read2.getCigarLength() >= 2) &&
				(Math.abs(read1.getAlignmentStart()-read2.getAlignmentStart()) < MAX_GAP_LENGTH)) {

				SAMRecord combinedRead = combineChimericReads(read1, read2);
				if (combinedRead != null) {
					isCombined = true;
					reads = new ArrayList<SAMRecord>();
					reads.add(combinedRead);
				}
			}
		}

		if (!isCombined) {
			reads = readList;
		}

		return reads;
	}

	private SAMRecord getTopHit(SAMRecord read1, SAMRecord read2) {
		SAMRecord topHit = null;

		if (isTopHit(read1)) {
			topHit = read1;
		} else if (isTopHit(read2)) {
			topHit = read2;
		}

		return topHit;
	}

	private boolean isTopHit(SAMRecord read) {
		// 0x800 = supplementary alignment from SAM spec v1.5
		return ((read.getFlags() & 0x800)  == 0) && (!read.getNotPrimaryAlignmentFlag());
	}

	private SAMRecord combineChimericReads(SAMRecord read1, SAMRecord read2) {
		SAMRecord left = null;
		SAMRecord right = null;

		if (read1.getAlignmentStart() < read2.getAlignmentStart()) {
			left = read1;
			right = read2;
		} else {
			left = read2;
			right = read1;
		}

		Cigar leftCigar = left.getCigar();
		Cigar rightCigar = right.getCigar();

		SAMRecord topHit = getTopHit(read1, read2);

		if ((rightCigar.getCigarElement(0).getOperator() == CigarOperator.S) &&
			(leftCigar.getCigarElement(left.getCigarLength()-1).getOperator() == CigarOperator.S) &&
			(topHit != null)) {

			List<CigarElement> leftElements = new ArrayList<CigarElement>();
			List<CigarElement> rightElements = new ArrayList<CigarElement>();

			// Drop trailing S on left side
			for (int i=0; i<left.getCigar().numCigarElements()-1; i++) {
				leftElements.add(leftCigar.getCigarElement(i));
			}

			// Drop leading S on right side
			for (int i=1; i<rightCigar.numCigarElements(); i++) {
				rightElements.add(rightCigar.getCigarElement(i));
			}

			// Encountered in dream test data at: chr20	22137016.  Not clear how to interpret.
			if (rightElements.get(0).getOperator() == CigarOperator.INSERTION) {
				return null;
			}

			// If total element length is longer than the read, then trim first element
			// on the left side of the indel (this is likely a deletion??)
//			if (totalLength > read1.getReadLength()) {
//				// Trim from the right side of the rightmos block of the left elements
//				int trimLength = totalLength - read1.getReadLength();
//				trimmedElemLength = trimRightmostElement(rightElements, trimLength);
//			}
//
//			if (trimmedElemLength < 0) {
//				// We don't currently handle the case where we need to trim more
//				// than the length of the element
//				return null;
//			}

			List<ReadBlock> leftBlocks = ReadBlock.getReadBlocks(left);
			ReadBlock lastLeftBlock = leftBlocks.get(leftBlocks.size()-2);

			List<ReadBlock> rightBlocks = ReadBlock.getReadBlocks(right);
			ReadBlock firstRightBlock = rightBlocks.get(1);

			// Confirm no shared bases in read
//			int leftStop = lastLeftBlock.getReadStart() + lastLeftBlock.getLength() - 1;
//			int rightStart = firstRightBlock.getReadStart();

			int leftStop = lastLeftBlock.getReferenceStop();
			int rightStart = firstRightBlock.getReferenceStart();

			int leftReadStop = lastLeftBlock.getReadStart() + lastLeftBlock.getLength() - 1;
			int rightReadStart = firstRightBlock.getReadStart();


			int trimLength = 0;
			if ((leftStop >= rightStart) || (leftReadStop >= rightReadStart)) {
				trimLength = Math.max(leftStop - rightStart + 1, leftReadStop - rightReadStart + 1);
				int trimmedElemLength = trimRightmostElement(leftElements, trimLength);
				if (trimmedElemLength < 1) {
					// This isn't really an indel.  Just alternate mappings.
//					System.err.println("Element trimmed too far. read1: [" + read1.getSAMString() +
//							"] read2: [" + read2.getSAMString() + "]");
					return null;
				}
			}

			// Build indel
			int leftAlignmentStop = lastLeftBlock.getReferenceStop();
			leftAlignmentStop -= trimLength;
			int rightAlignmentStart = firstRightBlock.getReferenceStart();

			leftReadStop -= trimLength;

			int alignmentGap = rightAlignmentStart - leftAlignmentStop - 1;
			int readGap = rightReadStart - leftReadStop - 1;

			CigarElement indelElement;
			if ((alignmentGap > 0) && (readGap == 0)) {
				// Deletion
				indelElement = new CigarElement(alignmentGap, CigarOperator.DELETION);
			} else {
				int totalLength = this.getTotalLength(leftElements, rightElements);
//				int insertLen = read1.getReadLength() - totalLength;
				int insertLen = read1.getReadLength() - totalLength - alignmentGap;
				int insertLen2 = readGap - alignmentGap;

				if (insertLen != insertLen2) {
					// Not really an insert
					return null;
				}

				//TODO: Take a closer look at this
				if (insertLen < 1) {
					return null;
				}

				// Right side may have skipped SNPs, so pad it.
				if (alignmentGap > 0) {
					this.padLeftmostElement(rightElements, alignmentGap);
				}

				//TODO: Necessary for mismatches?
				/*
				if ((readGap > 0) && ((totalLength + insertLen) < read1.getReadLength())) {
					if (readGap != read1.getReadLength() - totalLength - insertLen) {
						System.err.println("WARNING: Invalid read gap padding: " + read1.getSAMString());
					}
					this.padLeftmostElement(rightElements, readGap);
				}
				*/

				indelElement = new CigarElement(insertLen, CigarOperator.INSERTION);
			}

			// Check to see if the indel is surrounded by non-clipped bases
			if (minIndelBuffer > 0) {
				if ((getNonSoftClippedLength(leftElements) < minIndelBuffer) ||
					(getNonSoftClippedLength(rightElements) < minIndelBuffer)) {
					return null;
				}
			}

			List<CigarElement> elements = new ArrayList<CigarElement>();
			elements.addAll(leftElements);
			elements.add(indelElement);
			elements.addAll(rightElements);

			// Create combined read
			SAMRecord combinedRead = cloneRead(topHit);
			combinedRead.setAlignmentStart(leftBlocks.get(0).getReferenceStart());
			combinedRead.setCigar(new Cigar(elements));
			combinedRead.setMappingQuality((read1.getMappingQuality() + read2.getMappingQuality()) / 2);
//			combinedRead.setMappingQuality(Math.min(read1.getMappingQuality(), read2.getMappingQuality()));

			//TODO: Any other ancilliary info to set?

			return combinedRead;
		}

		return null;
	}

	private SAMRecord cloneRead(SAMRecord read) {
		try {
			return (SAMRecord) read.clone();
		} catch (CloneNotSupportedException e) {
			// Infamous "this should never happen" comment here.
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	private int trimRightmostElement(List<CigarElement> elements, int trimLength) {
		CigarElement toTrim = elements.get(elements.size()-1);
		int newLength = toTrim.getLength() - trimLength;
		CigarElement replacement = new CigarElement(newLength, toTrim.getOperator());
		elements.set(elements.size()-1, replacement);

		return newLength;
	}

	private int padLeftmostElement(List<CigarElement> elements, int padLength) {
		CigarElement toPad = elements.get(0);
		int newLength = toPad.getLength() + padLength;
		CigarElement replacement = new CigarElement(newLength, toPad.getOperator());
		elements.set(0, replacement);

		return newLength;
	}

	private int getTotalLength(List<CigarElement> elements) {
		int total = 0;

		for (CigarElement element : elements) {
			if (element.getOperator() != CigarOperator.D) {
				total += element.getLength();
			}
		}

		return total;
	}

	private int getNonSoftClippedLength(List<CigarElement> elements) {
		int total = 0;

		for (CigarElement element : elements) {
			if ((element.getOperator() != CigarOperator.D) && (element.getOperator() != CigarOperator.S)) {
				total += element.getLength();
			}
		}

		return total;
	}

	private int getTotalLength(List<CigarElement> left, List<CigarElement> right) {
		return getTotalLength(left) + getTotalLength(right);
	}

	static class ReadComparator implements Comparator<SAMRecord> {

		@Override
		public int compare(SAMRecord read1, SAMRecord read2) {
			int cmp = getMappedReadStart(read1) - getMappedReadStart(read2);

			if (cmp == 0) {
				cmp = getMappedReadEnd(read1) - getMappedReadEnd(read2);
			}

			return cmp;
		}
	}

	private void sortReadsByPosition(List<SAMRecord> reads) {
		Collections.sort(reads, new ReadComparator());
	}

	private void outputRead(SAMRecord read, boolean isCombined, SAMFileWriter out) {
		try {
			System.err.println(read.getSAMString());
			out.addAlignment(read);
		} catch (NullPointerException e) {
			System.err.println("isCombined: " + isCombined);
			System.err.println(read);
			System.err.println(read.getReadName());
			System.err.println(read.getSAMString());
			throw e;
		}
	}

	private void pruneLikelyInserts(List<SAMRecord> sortedReads) {
		if (sortedReads.size() == 3) {
			SAMRecord first  = sortedReads.get(0);
			SAMRecord middle = sortedReads.get(1);
			SAMRecord last   = sortedReads.get(2);

//			if ((middle.getAlignmentStart() <= first.getAlignmentEnd()) && (middle.getAlignmentEnd() >= last.getAlignmentStart())) {
//				sortedReads.remove(middle);
//			}

			int FUDGE_FACTOR = 5;

			if ((getMappedReadStart(middle) <= getMappedReadEnd(first) + FUDGE_FACTOR) && (getMappedReadEnd(middle) >= getMappedReadStart(last) - FUDGE_FACTOR)) {
				sortedReads.remove(middle);
			}
		}
	}

	// 1 based read start
	private static int getMappedReadStart(SAMRecord read) {

		List<ReadBlock> blocks = ReadBlock.getReadBlocks(read);

		for (ReadBlock block : blocks) {
			if (block.getType() != CigarOperator.S) {
				return block.getReadStart();
			}
		}

		return -1;
	}

	// 1 based read start (inclusive)
	private static int getMappedReadEnd(SAMRecord read) {
		List<ReadBlock> blocks = ReadBlock.getReadBlocks(read);

		for (int i=blocks.size()-1; i>=0; i--) {
			ReadBlock block = blocks.get(i);
			if (block.getType() != CigarOperator.S) {
				return block.getReadStart() + block.getLength() - 1;
			}
		}

		return -1;
	}

	/*
	public static void main(String[] args) {

		CombineChimera3 cc3 = new CombineChimera3();

		String in;
		String out;


		in = "/home/lmose/dev/abra/chim_insert_bug/t2.bam";
		out = "/home/lmose/dev/abra/chim_insert_bug/t2_out.bam";
		cc3.combine(in, out, 33);

	}
	*/
}
