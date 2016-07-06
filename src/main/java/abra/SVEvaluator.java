package abra;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import htsjdk.samtools.SAMFileHeader;

public class SVEvaluator {

	public void evaluateAndOutput(String svContigFasta, ReAligner realigner, String tempDir, int readLength, String[] inputSams, String[] tempDirs,
			SAMFileHeader[] samHeaders, String structuralVariantFile) throws IOException, InterruptedException {

		String svContigsSam = tempDir + "/" + "sv_contigs.sam";
		realigner.alignStructuralVariantCandidates(svContigFasta, svContigsSam);

		// Extract Breakpoint candidates
		SVHandler svHandler = new SVHandler(readLength, realigner.getMinMappingQuality());
		String svCandidates = tempDir + "/" + "sv_candidates.fa";
		boolean hasCandidates = svHandler.identifySVCandidates(svContigsSam, svCandidates);

		if (hasCandidates) {
			Aligner aligner = new Aligner(svCandidates, 1);
			aligner.index();

			String[] svSams = new String[inputSams.length];

			List<Map<String, Integer>> svCounts = new ArrayList<Map<String, Integer>>();
			Set<String> breakpointIds = new HashSet<String>();

			for (int i=0; i<inputSams.length; i++) {
				svSams[i] = tempDirs[i] + "/" + "sv_aligned_to_contig.sam";

				SVReadCounter svReadCounter = realigner.alignToSVContigs(tempDirs[i], svSams[i], svCandidates, null, samHeaders[i]);
				svCounts.add(svReadCounter.getCounts());
				breakpointIds.addAll(svReadCounter.getCounts().keySet());
//				realigner.alignToContigs(tempDirs[i], svSams[i], svCandidates, null, null);
			}

			/*
			for (int i=0; i<svSams.length; i++) {
				SVReadCounter svReadCounter = new SVReadCounter();
				Map<String, Integer> counts = svReadCounter.countReadsSupportingBreakpoints(svSams[i], readLength, samHeaders[i]);
				svCounts.add(counts);
				breakpointIds.addAll(counts.keySet());
			}
			*/

			BufferedWriter writer = new BufferedWriter(new FileWriter(structuralVariantFile, false));
			for (String breakpointId : breakpointIds) {
				writer.append(breakpointId.replace("+", "_"));
				for (Map<String, Integer> counts : svCounts) {
					writer.append('\t');
					if (counts.containsKey(breakpointId)) {
						writer.append(String.valueOf(counts.get(breakpointId)));
					} else {
						writer.append("0");
					}
				}
				writer.append('\n');
			}

			writer.close();
		}
	}
}
