package abra.cadabra;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import abra.CompareToReference2;
import abra.ReadAdjuster;

import htsjdk.samtools.Cigar;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.TextCigarCodec;

public class Cadabra {

	private static final int MIN_SUPPORTING_READS = 2;
	private static final int MIN_DISTANCE_FROM_READ_END = 3;
	private static final double MAX_NORMAL_OBS_AS_FRACTION_OF_TUMOR_OBS = 0.1;
	private static final double MIN_TUMOR_FRACTION = 0.02;

	private ReadLocusReader normal;
	private ReadLocusReader tumor;
	private CompareToReference2 c2r;

	public void callSomatic(String reference, String normal, String tumor) throws IOException {
		c2r = new CompareToReference2();
		c2r.init(reference);

		this.normal = new ReadLocusReader(normal);
		this.tumor = new ReadLocusReader(tumor);

		outputHeader();
		process();
	}

	private void outputHeader() {
		System.err.println("##fileformat=VCFv4.1");
		System.err.println("#CHROM	POS	ID	REF	ALT	QUAL	FILTER	INFO	FORMAT	NORMAL	TUMOR");
	}

	private void process() {
		Iterator<ReadsAtLocus> normalIter = normal.iterator();
		Iterator<ReadsAtLocus> tumorIter = tumor.iterator();

		ReadsAtLocus normalReads = null;
		ReadsAtLocus tumorReads = null;

		int count = 0;

		while (normalIter.hasNext() && tumorIter.hasNext()) {
			if (normalReads != null && tumorReads != null) {
				int compare = normalReads.compareLoci(tumorReads, normal.getSamHeader().getSequenceDictionary());

				if (compare < 0) {
					normalReads = normalIter.next();
				} else if (compare > 0) {
					tumorReads = tumorIter.next();
				} else {
					processLocus(normalReads, tumorReads);
					normalReads = normalIter.next();
					tumorReads = tumorIter.next();
				}

				if ((count % 1000000) == 0) {
					System.err.println("Position: " + normalReads.getChromosome() + ":" + normalReads.getPosition());
				}

				count += 1;
			} else {
				normalReads = normalIter.next();
				tumorReads = tumorIter.next();
			}
		}
	}

	private Character getBaseAtPosition(SAMRecord read, int refPos) {
		int readPos = 0;
		int refPosInRead = read.getAlignmentStart();
		int cigarElementIdx = 0;

		while (refPosInRead <= refPos && cigarElementIdx < read.getCigar().numCigarElements() && readPos < read.getReadLength()) {
			CigarElement elem = read.getCigar().getCigarElement(cigarElementIdx++);

			switch(elem.getOperator()) {
				case H: //NOOP
					break;
				case S:
				case I:
					readPos += elem.getLength();
					break;
				case D:
				case N:
					refPosInRead += elem.getLength();
					break;
				case M:
					if (refPos < (refPosInRead + elem.getLength())) {
						readPos += refPos - refPosInRead;
						if (readPos < read.getReadLength()) {
							// Found the base.  Return it
							return read.getReadString().charAt(readPos);
						}
					} else {
						readPos += elem.getLength();
						refPosInRead += elem.getLength();
					}
					break;
				default:
					throw new IllegalArgumentException("Invalid Cigar Operator: " + elem.getOperator() + " for read: " + read.getSAMString());
			}
		}

		return null;
	}

	private boolean matchesReference(SAMRecord read, int refPos) {
		boolean isMatch = false;

		if (!read.getReadUnmappedFlag()) {
			Character base = getBaseAtPosition(read, refPos);
			if (base != null) {
				String seq = c2r.getSequence(read.getReferenceName(), refPos, 1);
				isMatch = base.charValue() == seq.charAt(0);
			}
		}

		return isMatch;
	}

	private void processLocus(ReadsAtLocus normalReads, ReadsAtLocus tumorReads) {
		String chromosome = normalReads.getChromosome();
		int position = normalReads.getPosition();

		CigarElement tumorIndel = null;
		int tumorCount = 0;
		int tumorRefCount = 0;
		int mismatch0Count = 0;
		int mismatch1Count = 0;
		int totalMismatchCount = 0;
		boolean hasSufficientDistanceFromReadEnd = false;
		int maxContigMapq = 0;
		int minReadIndex = Integer.MAX_VALUE;
		int maxReadIndex = Integer.MIN_VALUE;
		int normalCount = 0;
		int normalRefCount = 0;

		Map<String, Integer> insertBasesMap = new HashMap<String, Integer>();

		for (SAMRecord read : tumorReads.getReads()) {
			if (!read.getDuplicateReadFlag()) {

				IndelInfo readElement = checkForIndelAtLocus(read, position);

				if (readElement != null) {
					Integer ymInt = (Integer) read.getAttribute(ReadAdjuster.MISMATCHES_TO_CONTIG_TAG);
					if (ymInt != null) {
						int ym = ymInt;
						if (ym == 0) {
							mismatch0Count++;
						}
						if (ym <= 1) {
							mismatch1Count++;
						}
						totalMismatchCount += ym;
					}
				} else if (matchesReference(read, position)) {
					tumorRefCount += 1;
				}

				if (tumorIndel == null && readElement != null) {
					tumorIndel = readElement.getCigarElement();
					tumorCount = 1;
					maxContigMapq = Math.max(maxContigMapq, read.getIntegerAttribute(ReadAdjuster.CONTIG_QUALITY_TAG));
					if (readElement.getInsertBases() != null) {
						updateInsertBases(insertBasesMap, readElement.getInsertBases());
					}
					minReadIndex = readElement.getReadIndex() < minReadIndex ? readElement.getReadIndex() : minReadIndex;
					maxReadIndex = readElement.getReadIndex() > maxReadIndex ? readElement.getReadIndex() : maxReadIndex;
				} else if (tumorIndel != null && readElement != null) {
					if (tumorIndel.equals(readElement.getCigarElement())) {
						// Increment tumor indel support count
						tumorCount += 1;
						maxContigMapq = Math.max(maxContigMapq, read.getIntegerAttribute(ReadAdjuster.CONTIG_QUALITY_TAG));
						if (readElement.getInsertBases() != null) {
							updateInsertBases(insertBasesMap, readElement.getInsertBases());
						}
						minReadIndex = readElement.getReadIndex() < minReadIndex ? readElement.getReadIndex() : minReadIndex;
						maxReadIndex = readElement.getReadIndex() > maxReadIndex ? readElement.getReadIndex() : maxReadIndex;

					} else {
						// We will not deal with multiple indels at a single locus for now.
						tumorIndel = null;
						tumorCount = 0;
						break;
					}
				}

				if (!hasSufficientDistanceFromReadEnd && tumorIndel != null && readElement != null && readElement.getCigarElement().equals(tumorIndel)) {
					hasSufficientDistanceFromReadEnd = sufficientDistanceFromReadEnd(read, readElement.getReadIndex());
				}
			}
		}

		float tumorFraction = (float) tumorCount / (float) tumorReads.getReads().size();

		if (tumorCount >= MIN_SUPPORTING_READS && hasSufficientDistanceFromReadEnd && tumorFraction >= MIN_TUMOR_FRACTION) {

			for (SAMRecord read : normalReads.getReads()) {
				if (!read.getDuplicateReadFlag()) {
					IndelInfo normalInfo = checkForIndelAtLocus(read.getAlignmentStart(), read.getCigar(), position);

					if (normalInfo != null && sufficientDistanceFromReadEnd(read, normalInfo.getReadIndex())) {
						normalCount += 1;
					} else if (normalInfo == null && matchesReference(read, position)) {
						normalRefCount += 1;
					}
				}
			}
		}

		if (tumorIndel != null && !isNormalCountOK(normalCount, normalReads.getReads().size(), tumorCount, tumorReads.getReads().size())) {
			tumorIndel = null;
			tumorCount = 0;
		}

		if (tumorCount >= MIN_SUPPORTING_READS && hasSufficientDistanceFromReadEnd && tumorFraction >= MIN_TUMOR_FRACTION) {
			String insertBases = null;
			if (tumorIndel.getOperator() == CigarOperator.I) {
				insertBases = getInsertBaseConsensus(insertBasesMap, tumorIndel.getLength());
			}

			int repeatPeriod = getRepeatPeriod(chromosome, position, tumorIndel, insertBases);

			double qual = calcPhredScaledQuality(normalRefCount, normalCount, tumorRefCount, tumorCount);

			outputRecord(chromosome, position, normalReads, tumorReads, tumorIndel,
					tumorCount, tumorRefCount, insertBases, maxContigMapq, mismatch0Count, mismatch1Count, totalMismatchCount, minReadIndex, maxReadIndex,
					normalCount, normalRefCount, repeatPeriod, qual);
		}
	}

	static double calcPhredScaledQuality(int normalRefObs, int normalAltObs, int tumorRefObs, int tumorAltObs) {
		FishersExactTest test = new FishersExactTest();
		// Calc p-value
		double p = test.oneTailedTest(normalRefObs, normalAltObs, tumorRefObs, tumorAltObs);

		// Convert to phred scale
		double qual = -10 * Math.log10(p);

		// Round to tenths
		qual = (int) (qual * 10);
		qual = qual / 10.0;

		return qual;
	}

	private int getRepeatPeriod(String chromosome, int position, CigarElement indel, String insertBases) {
		int chromosomeEnd = c2r.getReferenceLength(chromosome);
		int length = Math.min(indel.getLength() * 20, chromosomeEnd-position-2);
		String sequence = c2r.getSequence(chromosome, position+1, length);

		String bases;
		if (indel.getOperator() == CigarOperator.D) {
			bases = sequence.substring(0, indel.getLength());
		} else {
			bases = insertBases;
		}

		int period = 0;
		int index = 0;
		while ((index+bases.length() < length) && (bases.equals(sequence.substring(index, index+bases.length())))) {
			period += 1;
			index += bases.length();
		}

		return period;
	}

	private void updateInsertBases(Map<String, Integer> insertBases, String bases) {
		if (insertBases.containsKey(bases)) {
			insertBases.put(bases, insertBases.get(bases) + 1);
		} else {
			insertBases.put(bases, 1);
		}
	}

	private String getInsertBaseConsensus(Map<String, Integer> insertBases, int length) {
		int maxCount = -1;
		String maxBases = null;

		for (String bases : insertBases.keySet()) {
			int count = insertBases.get(bases);
			if (count > maxCount) {
				maxCount = count;
				maxBases = bases;
			}
		}

		if (maxBases == null) {
			StringBuffer buf = new StringBuffer(length);
			for (int i=0; i<length; i++) {
				buf.append('N');
			}
			maxBases = buf.toString();
		}

		return maxBases;
	}

	private boolean isNormalCountOK(int normalObs, int numNormalReads, int tumorObs, int numTumorReads) {
		if (numNormalReads == 0) {
			// Just proceed if normal depth is 0.
			return true;
		}

		double scalar = (double) numTumorReads / (double) numNormalReads;
		double scaledNormalObs = (double) normalObs * scalar;

		// Require normal observations to be less than 10% of tumor observations (normalized)
		return scaledNormalObs < tumorObs * MAX_NORMAL_OBS_AS_FRACTION_OF_TUMOR_OBS;
	}

	private boolean sufficientDistanceFromReadEnd(SAMRecord read, int readIdx) {
		boolean ret = false;

		if (readIdx >= MIN_DISTANCE_FROM_READ_END &&
			readIdx <= read.getReadLength()-MIN_DISTANCE_FROM_READ_END-1) {
				ret = true;
		}

		return ret;
	}

	private String getDelRefField(String chromosome, int position, int length) {
		return c2r.getSequence(chromosome, position, length+1);
	}

	private String getInsRefField(String chromosome, int position) {
		return c2r.getSequence(chromosome, position, 1);
	}

	private void outputRecord(String chromosome, int position,
			ReadsAtLocus normalReads, ReadsAtLocus tumorReads, CigarElement indel,
			int tumorObs, int tumorRefObs, String insertBases, int maxContigMapq, int ym0, int ym1, int totalYm,
			int minReadIndex, int maxReadIndex, int normalObs, int normalRefObs, int repeatPeriod,
			double qual) {

		int normalDepth = normalReads.getReads().size();
		int tumorDepth = tumorReads.getReads().size();

		String context = c2r.getSequence(chromosome, position-10, 20);

		StringBuffer buf = new StringBuffer();
		buf.append(chromosome);
		buf.append('\t');
		buf.append(position);
		buf.append("\t.\t");

		String ref = ".";
		String alt = ".";
		if (indel.getOperator() == CigarOperator.D) {
			ref = getDelRefField(chromosome, position, indel.getLength());
			alt = ref.substring(0, 1);
		} else if (indel.getOperator() == CigarOperator.I) {
			ref = getInsRefField(chromosome, position);
			alt = ref + insertBases;
		}

		buf.append(ref);
		buf.append('\t');
		buf.append(alt);
		buf.append("\t");
		buf.append(qual);
		buf.append("\tPASS\t");
		buf.append("SOMATIC;CMQ=" + maxContigMapq + ";CTX=" + context + ";REPEAT_PERIOD=" + repeatPeriod);
		buf.append("\tDP:AD:YM0:YM1:YM:OBS:MIRI:MARI\t");
		buf.append(normalDepth);
		buf.append(':');
		buf.append(normalRefObs);
		buf.append(',');
		buf.append(normalObs);
		buf.append(":0:0:0:0:0:0");
		buf.append('\t');
		buf.append(tumorDepth);
		buf.append(':');
		buf.append(tumorRefObs);
		buf.append(',');
		buf.append(tumorObs);
		buf.append(':');
		buf.append(ym0);
		buf.append(':');
		buf.append(ym1);
		buf.append(':');
		buf.append(totalYm);
		buf.append(':');
		buf.append(tumorObs);
		buf.append(':');
		buf.append(minReadIndex);
		buf.append(':');
		buf.append(maxReadIndex);

		System.err.println(buf.toString());
	}


	private IndelInfo checkForIndelAtLocus(SAMRecord read, int refPos) {
		IndelInfo elem = null;

		String contigInfo = read.getStringAttribute("YA");
		if (contigInfo != null) {
			// Get assembled contig info.
			String[] fields = contigInfo.split(":");
			int contigPos = Integer.parseInt(fields[1]);

//			Cigar contigCigar = TextCigarCodec.getSingleton().decode(fields[2]);
			Cigar contigCigar = TextCigarCodec.decode(fields[2]);

			// Check to see if contig contains indel at current locus
			elem = checkForIndelAtLocus(contigPos, contigCigar, refPos);

			if (elem != null) {
				// Now check to see if this read supports the indel
				IndelInfo readElem = checkForIndelAtLocus(read.getAlignmentStart(),
						read.getCigar(), refPos);

				// Allow partially overlapping indels to support contig
				// (Should only matter for inserts)
				if (readElem == null || readElem.getCigarElement().getOperator() != elem.getCigarElement().getOperator()) {
					// Read element doesn't match contig indel
					elem = null;
				} else {
					elem.setReadIndex(readElem.getReadIndex());

					// If this read overlaps the entire insert, capture the bases.
					if (elem.getCigarElement().getOperator() == CigarOperator.I &&
						elem.getCigarElement().getLength() == readElem.getCigarElement().getLength()) {

						String insertBases = read.getReadString().substring(readElem.getReadIndex(), readElem.getReadIndex()+readElem.getCigarElement().getLength());
						elem.setInsertBases(insertBases);
					}
				}
			}
		}

		return elem;
	}


	private IndelInfo checkForIndelAtLocus(int alignmentStart, Cigar cigar, int refPos) {

		IndelInfo ret = null;

		int readIdx = 0;
		int currRefPos = alignmentStart;
		for (CigarElement element : cigar.getCigarElements()) {
			if (element.getOperator() == CigarOperator.M) {
				readIdx += element.getLength();
				currRefPos += element.getLength();
			} else if (element.getOperator() == CigarOperator.I) {
				if (currRefPos == refPos+1) {
					ret = new IndelInfo(element, readIdx);
					break;
				}
				readIdx += element.getLength();
			} else if (element.getOperator() == CigarOperator.D) {
				if (currRefPos == refPos+1) {
					ret = new IndelInfo(element, readIdx);
					break;
				}
				currRefPos += element.getLength();
			} else if (element.getOperator() == CigarOperator.S) {
				readIdx += element.getLength();
			}
		}

		return ret;
	}

	private char getReadBase(SAMRecord read, int index) {
		return (char) read.getReadBases()[index];
	}

	public static void main(String[] args) throws Exception {
//		String normal = "/home/lmose/dev/abra/cadabra/normal_test2.bam";
//		String tumor = "/home/lmose/dev/abra/cadabra/tumor_test2.bam";

//		String reference = "/home/lmose/reference/chr1/1.fa";
//		String normal = "/home/lmose/dev/abra/cadabra/normal1.bam";
//		String tumor = "/home/lmose/dev/abra/cadabra/tumor1.bam";


//		String normal = "/home/lmose/dev/abra/cadabra/normal.abra4.sort.bam";
//		String tumor = "/home/lmose/dev/abra/cadabra/tumor.abra4.sort.bam";

//		String reference = "/home/lmose/reference/chr1/chr1.fa";
//		String normal = "/home/lmose/dev/abra/cadabra/t2/ntest.bam";
//		String tumor = "/home/lmose/dev/abra/cadabra/t2/ttest.bam";


//		String reference = "/home/lmose/reference/chr1/chr1.fa";
//		String normal = "/home/lmose/dev/abra/cadabra/ins/ntest.bam";
//		String tumor = "/home/lmose/dev/abra/cadabra/ins/ttest.bam";

		if (args.length < 3) {
			System.err.println("Usage: java -cp abra.jar abra.cadabra.Cadabra <reference> <normal_bam> <tumor_bam>");
			System.exit(-1);
		}

		String reference = args[0];
		String normal = args[1];
		String tumor = args[2];

		new Cadabra().callSomatic(reference, normal, tumor);
	}
}
