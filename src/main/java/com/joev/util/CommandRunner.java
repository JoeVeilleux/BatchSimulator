package com.joev.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class CommandRunner {

	private static final SimpleLogger log = new SimpleLogger(CommandRunner.class.getSimpleName());

	private static final String sNL = System.getProperty("line.separator");
	private static final String sOSName = System.getProperty("os.name");
	private static final String sCMD;
	private static final String sCMDArg1;
	static {
		if (sOSName.startsWith("Windows")) {
			sCMD = "cmd";
			sCMDArg1 = "/C";
		} else if (sOSName.startsWith("Linux")) {
			sCMD = "/bin/sh";
			sCMDArg1 = "-c";
		} else {
			throw new IllegalArgumentException("Unable to determine OSType! System.getProperty(os.name)='"+sOSName+"'. Expecting 'Windows.*' or 'Linux.*'.");
		}
	}

	/**
	 * Utility routine to run an OS command
	 * @param sCommand = Command to be run
	 * @param fDir = Working directory to set, before running the command
	 * @return
	 * @throws Exception
	 */
	public CommandRunnerResult runCommand(String sCommand, File fDir) throws Exception {
		log.log("Running command: '"+sCommand+"' using working directory '"+fDir.getAbsolutePath()+"'...");
		Runtime rt = Runtime.getRuntime();
		String lsCommand[] = { sCMD, sCMDArg1, sCommand };
		Process proc = rt.exec(lsCommand, null, fDir);
		int iRC = proc.waitFor();
		log.log("Command '"+sCommand+"' completed. RC="+iRC);
		// Get STDOUT...
		BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
		StringBuffer sbSTDOUT = new StringBuffer();
		String sLine = null;
		while ((sLine = br.readLine()) != null) {
			if (sbSTDOUT.length() > 0)
				sbSTDOUT.append(sNL);
			sbSTDOUT.append(sLine);
		}		
		// Get STDERR...
		br = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
		StringBuffer sbSTDERR = new StringBuffer();
		sLine = null;
		while ((sLine = br.readLine()) != null) {
			if (sbSTDERR.length() > 0)
				sbSTDERR.append(sNL);
			sbSTDERR.append(sLine);
		}
		// Return the result of running the command...
		CommandRunnerResult crr = new CommandRunnerResult(sCommand, fDir, iRC, sbSTDOUT.toString(), sbSTDERR.toString());
		return crr;
	}

	public CommandRunner() {
	}
	
	public class CommandRunnerResult {
		public String sCommand;
		public File fDir;
		public int iRC;
		public String sSTDOUT;
		public String sSTDERR;
		CommandRunnerResult(String sCommand, File fDir, int iRC, String sSTDOUT, String sSTDERR) {
			this.sCommand = sCommand;
			this.fDir = fDir;
			this.iRC = iRC;
			this.sSTDOUT = sSTDOUT;
			this.sSTDERR = sSTDERR;
		}
		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append("  Command: ").append(this.sCommand).append(sNL);
			sb.append("  Working Directory: ").append(this.fDir).append(sNL);
			sb.append("  RC: ").append(this.iRC).append(sNL);
			if (this.sSTDOUT.equals("")) {
				sb.append("  STDOUT: (empty)").append(sNL);
			} else {
				int iLineNo = 0;
				for (String sLine : this.sSTDOUT.split("[\r\n]+")) {
					iLineNo++;
					sb.append(String.format("  STDOUT:%04d: %s", iLineNo, sLine)).append(sNL);
				}
			}
			if (this.sSTDERR.equals("")) {
				sb.append("  STDERR: (empty)").append(sNL);
			} else {
				int iLineNo = 0;
				for (String sLine : this.sSTDERR.split("[\r\n]+")) {
					sb.append(String.format("  STDERR:%04d: %s", iLineNo, sLine)).append(sNL);
					iLineNo++;
				}
			}
			return sb.toString();
		}
	}

	public static void main(String[] args) throws Exception {
		log.log("Starting...");
		
		String sCommand = "ls";
		File fDir = new File("/tmp");
		
		CommandRunner cr = new CommandRunner();
		CommandRunnerResult crr = cr.runCommand(sCommand, fDir);
		log.log("Command returned result:\n"+crr.toString());
		
		log.log("Done.");
	}

}
