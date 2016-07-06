/* Copyright 2013 University of North Carolina at Chapel Hill.  All rights reserved. */
package abra;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Handles alignment for contigs and short reads.
 *
 * @author Lisle E. Mose (lmose at unc dot edu)
 */
public class Aligner {

	private String reference;
	private int numThreads;

	public Aligner(String reference, int numThreads) {
		this.reference = reference;
		this.numThreads = numThreads;
	}

	public void align(String input, String outputSam, boolean isGapExtensionFavored) throws IOException, InterruptedException {

		String cmd = "bwa mem -t " + numThreads + " " + reference + " " + input + " > " + outputSam;

		runCommand(cmd);
	}

	private void runCommand(String cmd) throws IOException, InterruptedException {
		runCommand(cmd, null);
	}

	private void runCommand(String cmd, StdoutHandler stdoutHandler) throws IOException, InterruptedException {

		System.err.println("Running: [" + cmd + "]");

		long s = System.currentTimeMillis();

		String[] cmds = {
				"bash",
				"-c",
				cmd
			};
		Process proc = Runtime.getRuntime().exec(cmds);

		Thread stdout = null;

		if (stdoutHandler != null) {
			stdoutHandler.process(proc);
		} else {
			stdout = new Thread(new CommandOutputConsumer(proc, proc.getInputStream()));
			stdout.start();
		}

		Thread stderr = new Thread(new CommandOutputConsumer(proc, proc.getErrorStream()));
		stderr.start();

		int ret = proc.waitFor();

		if (stdoutHandler != null) {
			stdoutHandler.postProcess();
		} else {
			stdout.join();
		}

		stderr.join();

		long e = System.currentTimeMillis();

		System.err.println("BWA time: " + (e-s)/1000 + " seconds.");

		if (ret != 0) {
			throw new RuntimeException("BWA exited with non-zero return code : [" + ret + "] for command: [" + cmd + "]");
		}
	}

	public void shortAlign(String input, String outputSam, StdoutHandler stdoutHandler, boolean isBamInput) throws IOException, InterruptedException {

		// Throttle back the number of threads to accomodate the stdout processing.
		int threads = Math.max(numThreads-2, 1);

		String bamFlag = isBamInput ? " -b " : " ";

//		String map = "bwa aln " + reference + " " + input + " -b -t " + threads + " -o 0 | bwa samse " + reference + " - " + input + " -n 1000";
		String map = "bwa aln " + reference + " " + input + bamFlag + "-t " + threads + " -o 0 | bwa samse " + reference + " - " + input + " -n 1000";

		// Redirect stdout to file if no stdout consumer provided.
		if (stdoutHandler == null) {
			map += " > " + outputSam;
		}

		runCommand(map, stdoutHandler);
	}

	public void index() throws IOException, InterruptedException {

		if (shouldUseSmallIndex()) {
			runCommand("bwa index " + reference);
		} else {
			runCommand("bwa index -a bwtsw " + reference);
		}
	}

	private boolean shouldUseSmallIndex() throws IOException {
		BufferedReader reader = new BufferedReader(new FileReader(reference));

		try {
			int lines = 0;
			String line = reader.readLine();
			while (line != null) {
				lines++;
				line = reader.readLine();

				if (lines >= 1000000) {
					return false;
				}
			}
		} finally {
			reader.close();
		}

		return true;
	}

	static class CommandOutputConsumer implements Runnable {

		private Process proc;
		private InputStream stream;

		CommandOutputConsumer(Process proc, InputStream stream) {
			this.proc = proc;
			this.stream = stream;
		}

		@Override
		public void run() {
			InputStreamReader isr = new InputStreamReader(stream);
			BufferedReader br = new BufferedReader(isr);
			String line = null;

			try {
				while ( (line = br.readLine()) != null) {
					System.err.println(line);
				}

				br.close();
				isr.close();
			} catch (IOException e) {
				e.printStackTrace();
				throw new RuntimeException(e);
			}

			System.err.println("Stream thread done.");
		}

	}

	public static void main(String[] args) throws Exception {
		Aligner a = new Aligner("/datastore/nextgenout2/share/labs/UNCseq/lmose2/mapzilla/bwamem/ref/hg19.fa", 8);

		a.align(args[0], args[1], true);
	}
}
