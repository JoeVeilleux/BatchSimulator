package com.joev.batch;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.joev.util.CommandRunner;
import com.joev.util.CommandRunner.CommandRunnerResult;
import com.joev.util.SimpleLogger;
import com.joev.util.SimpleLogger.LogFormat;

public class BatchSimulator {
	
	private static final SimpleLogger log = new SimpleLogger(BatchSimulator.class.getSimpleName());
	private static final String sNL = System.getProperty("line.separator");
	private static final String sPropFileName = "BatchSimulator.properties";
	private static Properties props = null;
	private static int iJobNumber = 1;
	
	private static final boolean bVerbose = true; // FIXME: false;

	private static final HashMap<String,File> alfSpoolDir = new HashMap<String,File>();
	private static final SimpleDateFormat sdfLastMod = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	private static final SimpleDateFormat sdfRunDTOutputFilename = new SimpleDateFormat("yyyyMMddHHmmssSSS");
	
	// Time (seconds) to wait after each job completes
	private static final int iWaitAfterEachJob = 10;
	// Time (seconds) to wait for new work to come in when input queue is empty...
	private static final int iWaitForWork = 10; 

	enum RunMode { SingleThreadAllFiles, SingleThreadWaitForStop }
	
	private enum PARSETOKEN {
		JOB("^\\$JOB (.*)"), COMPILE("^\\$(PY|JAVA|C) (\\w+).*"), CODEORRUN("^\\$RUN.*"), INPUTDATAOREND("^\\$END.*"), ERROREXIT("^\\s+$");
		private Pattern pat = null;
		PARSETOKEN(String sPat) { 
			this.pat = Pattern.compile(sPat);
		};
		public Matcher matcher(String s) {
			Matcher m = this.pat.matcher(s);
			return m;
		}
	}
	
	/**
	 * Parse an input spool file into a set of batch jobs
	 * @param fInput a File which is expected to contain one or more batch jobs
	 * @return ArrayList of BatchJob objects representing the individual jobs from the file
	 */
	private static BatchJob parseBatchJob(File fInput) throws Exception {

		iJobNumber++;
		
		// Read and parse the input stream, which is expected to consist of one batch job:
		// $JOB <comments>
		// $PY|JAVA|C <MainClass>
		//   ...code of a Python, Java, or C program...
		// $RUN
		//   ...input data for the program...
		// $END
		BufferedReader brInputJobs = null;
		log.log("Reading input from file '"+fInput.getAbsolutePath()+"'...");
		// FIXME: This method of reading only supports files in the default encoding, which appears to be ASCII (not UTF-8, etc)
		brInputJobs = new BufferedReader(new FileReader(fInput));
		PARSETOKEN ptExpect = PARSETOKEN.JOB;
		StringBuffer sbJob = new StringBuffer();
		String sJobComments = null;
		String sParseError = null;
		StringBuffer sbPgmCode = new StringBuffer();
		StringBuffer sbInputData = new StringBuffer();
		String sCompilerLang = null;
		String sPgmName = null;

		String sLine = null;
		int iLine = 0;
		// Read the whole file until EOF
		while ((sLine = brInputJobs.readLine()) != null) {
			iLine++;
			// Accumulate a buffer containing the full text of this BatchJob...
			if (sbJob.length() > 0)
				sbJob.append(sNL);
			sbJob.append(sLine);
			if (bVerbose) log.log("PARSER: Read input line #"+iLine+": "+sLine);
			// Parse this input line, using the current parse context...
			Matcher m = ptExpect.matcher(sLine);
			switch (ptExpect) {
			case JOB:
				if (m.matches()) {
					sJobComments = m.group(1);
					if (bVerbose) log.log("PARSER: Recognized '$JOB'. Comments='"+sJobComments+"'");
					ptExpect = PARSETOKEN.COMPILE;
				} else {
					sParseError = "ERROR: Input line #"+iLine+" '"+sLine+"' does not match expected pattern '"+ptExpect.pat.pattern()+"'";
					log.log(sParseError);
					ptExpect = PARSETOKEN.ERROREXIT;
				}
				break;
			case COMPILE:
				if (m.matches()) {
					sCompilerLang = m.group(1);
					sPgmName = m.group(2);
					if (bVerbose) log.log("PARSER: Recognized '$"+sCompilerLang+"'. PgmName='"+sPgmName+"'");
					ptExpect = PARSETOKEN.CODEORRUN;
				} else {
					sParseError = "ERROR: Input line #"+iLine+" '"+sLine+"' does not match expected pattern '"+ptExpect.pat.pattern()+"'";
					log.log(sParseError);
					ptExpect = PARSETOKEN.ERROREXIT;
				}
				break;
			case CODEORRUN:
				if (m.matches()) {
					if (bVerbose) log.log("PARSER: Recognized '$RUN'.");
					ptExpect = PARSETOKEN.INPUTDATAOREND;
				} else {
					// It must be a (another?) line of program code...
					if (sbPgmCode.length() > 0)
						sbPgmCode.append(sNL);
					sbPgmCode.append(sLine);
				}
				break;
			case INPUTDATAOREND:
				if (m.matches()) {
					if (bVerbose) log.log("PARSER: Recognized '$END'.");
					ptExpect = PARSETOKEN.ERROREXIT;
				} else {
					// It must be a (another?) line of input data...
					if (sbInputData.length() > 0)
						sbInputData.append(sNL);
					sbInputData.append(sLine);
				}
				break;
			case ERROREXIT:
				if (m.matches()) {
					// Extraneous blank line at the end of the file. Don't flag this as an error.
				} else {
					// If we have not yet caught/reported any parse error, report this one
					if (sParseError == null) {
						sParseError = "ERROR: Unexpected text at end of file: '" + sLine + "'";
						log.log(sParseError);
					}
				}
				break;
			default:
				sParseError = "ERROR: Unexpected value '"+ptExpect+"' for parse-status flag!";
				log.log(sParseError);
				throw new IllegalArgumentException(sParseError);
			}
		}
		brInputJobs.close();
		
		log.log("At end of input loop:");
		log.log("Number of lines read: "+iLine);

		return new BatchJob(fInput.getName(), sParseError, iJobNumber, sbJob.toString(), sJobComments, sCompilerLang, sPgmName, sbPgmCode.toString(), sbInputData.toString());
	}
	
	private static class BatchJob implements Runnable {
		private SimpleLogger log = null;
		private String sFilename;
		private String sParseError;
		private int iJobNumber;
		private String sJob;
		private String sJobComments;
		private String sCompilerLang;
		private String sPgmName;
		private String sPgmCode;
		private String sInputData;
		private Date dtRun = null;
		private CommandRunnerResult crrCompile = null;
		private CommandRunnerResult crrRun = null;
		
		BatchJob(String sFilename, String sParseError, int iJobNumber, String sJob, String sJobComments, String sCompilerLang, String sPgmName, String sPgmCode, String sInputData) {
			this.sFilename = sFilename;
			this.sParseError = sParseError;
			this.iJobNumber = iJobNumber;
			this.sJob = sJob;
			this.sJobComments = sJobComments;
			this.sCompilerLang = sCompilerLang;
			this.sPgmName = sPgmName;
			this.sPgmCode = sPgmCode;
			this.sInputData = sInputData;
			this.log = new SimpleLogger(this.getClass().getSimpleName()+":"+sFilename+":"+String.format("%04d", iJobNumber)+":"+Thread.currentThread().getName());
		}
		
		public String toString() {
			StringBuffer sb = new StringBuffer();
			sb.append("[").append(this.getClass().getSimpleName()).append(":");
			sb.append(" Filename=").append(this.sFilename);
			sb.append(" JobNumber=").append(this.iJobNumber);
			sb.append(" Thread=").append(Thread.currentThread().getName());
			sb.append(" ]");
			return sb.toString();
		}
		
		public void run() {
			log.log("run() starting...");
			// Set the date/time that we are starting this execution...
			this.dtRun = new Date();
			// Build the output filename...
			String sOutputFilename =
				sdfRunDTOutputFilename.format(this.dtRun)
				+"_"+String.format("%04d", iJobNumber)
				+"_"+this.sFilename
			;
			File fOutputFile = new File(alfSpoolDir.get("Output"), sOutputFilename);
			// Set the log to write to the output file (in addition to STDOUT)...
			log.setLogFile(fOutputFile);
			// Write a block banner at the top, summarizing all characteristics of this job...
			log.log(
				"Running BatchJob with:"+sNL
				+"  JobNumber: "+this.iJobNumber+sNL
				+"  Filename: "+this.sFilename+sNL
				+"  JobComments: "+this.sJobComments+sNL
				, LogFormat.Banner
			);

			// Log the raw job input...
			log.log("Job input:");
			log.log(sNL+this.sJob+sNL+" "+sNL, LogFormat.Raw);
			
			// If there was an error parsing this job, just create the log file containing as much as we know about
			// the error
			if (this.sParseError != null) {
				log.log("Run aborted due to job parsing error: " + this.sParseError);
				log.log("run() done.");
				// Set the log back to just writing to STDOUT...
				log.setLogFile(null);
				return;
			}
			
			int iRC = 0;
			CommandRunner cmdRunner = new CommandRunner();
			
			log.log(this.sCompilerLang+" program code");
			log.log(this.sPgmCode, LogFormat.WithLineNumbers);
			log.log("Input data");
			log.log(this.sInputData, LogFormat.WithLineNumbers);
			
			// Create a temporary directory, and put the program and input-data files into it...
			log.log("Spooling program and data to disk...");
			String sSysTmpDir = System.getProperty("java.io.tmpdir");
			String sTmpDirName = "BatchSimulator"+System.currentTimeMillis()+".tmp";
			File fTmpDir = new File(sSysTmpDir, sTmpDirName);
			if (!fTmpDir.mkdirs()) {
				log.log("ERROR: Unable to create temp directory '"+fTmpDir.getAbsolutePath()+"! Unable to run program!");
				iRC = 16;
			}
			
			try {
				if (iRC == 0) {
					log.log("Temp directory '"+fTmpDir.getAbsolutePath()+"' created.");
					File fProgram = new File(fTmpDir, sPgmName+"."+sCompilerLang.toLowerCase());
					FileWriter fwProgram;
					fwProgram = new FileWriter(fProgram);
					fwProgram.write(sPgmCode);
					fwProgram.close();
					log.log("Wrote "+sCompilerLang+" program to: '"+fProgram.getAbsolutePath()+"'.");
					File fInputData = new File(fTmpDir, "INPUTDATA.txt");
					FileWriter fwInputData = new FileWriter(fInputData);
					fwInputData.write(sInputData);
					fwInputData.close();
					log.log("Wrote input data to: '"+fInputData.getAbsolutePath()+"'.");
					
					// Compile the program...
					if (sCompilerLang.matches("(JAVA|C)")) {
						log.log("Compiling "+sCompilerLang+" program...");
						if (sCompilerLang.equals("JAVA")) {
							this.crrCompile = cmdRunner.runCommand("javac "+sPgmName+".java", fTmpDir);
						} else if (sCompilerLang.equals("C")) {
							this.crrCompile = cmdRunner.runCommand("gcc -o "+sPgmName+" "+sPgmName+".c", fTmpDir);
						}
						log.log("Compilation command result:");
						log.log(this.crrCompile.toString());
						if (crrCompile.iRC == 0) {
							log.log("Compilation successful!");
						} else {
							log.log("Compilation unsuccessful!");
						}
						iRC = this.crrCompile.iRC;
					}
					
					// Execute the program (but only if it compiled cleanly)...
					if (iRC == 0) {
						log.log("Executing program...");
						if (sCompilerLang.equals("PY")) {
							this.crrRun = cmdRunner.runCommand("python3 "+sPgmName+".py < "+fInputData.getName(), fTmpDir);
						} else if (sCompilerLang.equals("JAVA")) {
							this.crrRun = cmdRunner.runCommand("java "+sPgmName+" < "+fInputData.getName(), fTmpDir);
						} else if (sCompilerLang.equals("C")) {
							this.crrRun = cmdRunner.runCommand("./"+sPgmName+" < "+fInputData.getName(), fTmpDir);
						} else {
							throw new IllegalArgumentException("Unrecognized language: "+sCompilerLang);
						}
						log.log("Program execution result:");
						log.log(this.crrRun.toString());
						iRC = this.crrRun.iRC;
						if (crrRun.iRC == 0) {
							log.log("Program execution successful!");
						} else {
							log.log("Program execution unsuccessful!");
						}
					}
					
					// Clean up...
					log.log("Cleaning up...");
					new File(fTmpDir, sPgmName+".class").delete();
					fProgram.delete();
					fInputData.delete();
					fTmpDir.delete();
				}
			} catch (Exception e) {
				log.log("Caught exception running job: "+e.toString());
				e.printStackTrace();
			}
			
			log.log("run() done.");
			// Set the log back to just writing to STDOUT...
			log.setLogFile(null);
		}
	}

	private static void loadProps() throws Exception {
		props = new Properties();
		URL urlProperties = BatchSimulator.class.getResource("/"+sPropFileName);
		InputStream isProperties = BatchSimulator.class.getResourceAsStream("/"+sPropFileName);
		props.load(isProperties);
		log.log("Properties loaded from "+urlProperties);
		log.log("Values: "+props.toString());
	}
	
	private static void setUpSpool() {
		File fSpoolParentDir = new File(props.getProperty("SpoolParentDir"));
		alfSpoolDir.put("Parent", fSpoolParentDir);
		alfSpoolDir.put("Input",  new File(fSpoolParentDir, "Input"));
		alfSpoolDir.put("Output",  new File(fSpoolParentDir, "Output"));
		String slDirs[] = { "Parent", "Input", "Output" };
		for (String sDir : slDirs ) {
			File f = alfSpoolDir.get(sDir);
			log.log("Spool '"+sDir+"' directory: "+f.getAbsolutePath());
			if (f.exists()) {
				if (f.isDirectory())
					log.log("Directory exists.");
				else
					throw new IllegalArgumentException("Spool "+sDir+" directory '"+f.getAbsolutePath()+"' exists, but is not a directory!");
			} else {
				log.log("Directory does not exist; creating...");
				if (f.mkdirs())
					log.log("Directory created successfully.");
				else
					throw new IllegalArgumentException("Unable to create spool '"+sDir+"' directory!");
			}
		}
	}
		
	private static ArrayList<File> listSpoolFiles(String sSubDir) {
		File fSpoolDir = alfSpoolDir.get(sSubDir);
		log.log("'"+sSubDir+"' queue (from '"+fSpoolDir.getAbsolutePath()+"')...");
		ArrayList<File> alf = null;
		if (fSpoolDir.exists()) {
			if (fSpoolDir.isDirectory()) {
				alf = new ArrayList<File>(Arrays.asList(fSpoolDir.listFiles()));
				// Sort by last-modified, oldest first...
				Collections.sort(alf, new Comparator<File>() {
					public int compare(File f1, File f2) {
						return (int) (f1.lastModified() - f2.lastModified());
					}
				});
				// Display the files...
				log.log("  Date/Time          Length Filename");
				log.log("  ------------------ ------ ----------------------------------------");
				for (File f : alf) {
					log.log(String.format(
						"  %s %6d %s",
						sdfLastMod.format(f.lastModified()),
						f.length(),
						f.getName()
					));
				}
				log.log("  Total files: "+alf.size());
			} else {
				log.log("ERROR: '"+sSubDir+"' queue directory '"+fSpoolDir.getAbsolutePath()+" exists but is not a directory!");
			}
		} else {
			log.log("ERROR: '"+sSubDir+"' queue directory '"+fSpoolDir.getAbsolutePath()+" does not exist!");
		}
		return alf;
	}
	
	public static void waitBetweenJobs() {
		// Delay for a bit, to simulate an actual slow computer...
		log.log("Taking a short ("+iWaitAfterEachJob+" second) break...");
		try { Thread.sleep(1000*iWaitAfterEachJob); } catch (InterruptedException e) { /* Ignore */ }
		log.log("Now I'm well rested. Going back for more work...");
	}

	public static void main(String[] args) throws Exception {
		log.log("Starting...");

		// Get global runtime properties...
		loadProps();
		RunMode runMode = RunMode.valueOf(props.getProperty("RunMode"));
		log.log("RunMode: "+runMode);
		
		// Set up (create, if necessary) the input and output spool directories...
		setUpSpool();
		
		// Run the jobs, using the specified RunMode...
		if (runMode.equals(RunMode.SingleThreadAllFiles)) {
			// SingleThreadAllFiles:
			// For each file in the spool input directory
			//   - Parse the file into its list of BatchJobs
			//   - For each BatchJob
			//     - Run it
			ArrayList<File> alfInput = listSpoolFiles("Input");
			for (File f : alfInput) {
				log.log("Processing BatchJob file: "+f.getAbsolutePath());
				BatchJob bj = parseBatchJob(f);
				bj.run();
				log.log("Done with BatchJob file: "+f.getAbsolutePath()+". Deleting it...");
				log.log("Deleting BatchJob file: "+f.getAbsolutePath()+"...");
				log.log(f.delete() ? "File deleted successfully." : "Error deleting file. Unable to delete!");
				waitBetweenJobs();
			}
		} else if (runMode.equals(RunMode.SingleThreadWaitForStop)) {
			// SingleThreadWaitForStop:
			// For each file in the spool input directory
			//   - Parse the file into its list of BatchJobs
			//   - For each BatchJob
			//     - Run it
			// When queue is empty, sleep waiting for new work.
			// When "stop" command file is created, terminate
			String sStopCommandFilename = "_BatchSimulator_STOP_";
			File fStop = new File(alfSpoolDir.get("Input"), sStopCommandFilename);
			boolean bStopCommandReceived = false;
			while (!bStopCommandReceived) {
				if (fStop.exists()) {
					bStopCommandReceived = true;
					log.log("Stop-command file '"+sStopCommandFilename+"' found. Exiting.");
					// Delete the stop-command file, now that we've noticed it...
					fStop.delete();
				} else {
					ArrayList<File> alfInput = listSpoolFiles("Input");
					if (alfInput.size() > 0) {
						// Process the first file on the list (note the first one is the oldest)...
						File f = alfInput.remove(0);
						log.log("Processing BatchJob file: "+f.getAbsolutePath());
						BatchJob bj = parseBatchJob(f);
						bj.run();
						log.log("Done with BatchJob file: "+f.getAbsolutePath()+". Deleting it...");
						log.log("Deleting BatchJob file: "+f.getAbsolutePath()+"...");
						log.log(f.delete() ? "File deleted successfully." : "Error deleting file. Unable to delete!");
						waitBetweenJobs();
					} else {
						log.log("No jobs in queue. Create file '"+sStopCommandFilename+"' to stop. Waiting...");
						Thread.sleep(1000*iWaitForWork);
						log.log("Done waiting. Checking for more work...");
					}
				}
			}
		} else {
			String sMsg = "ERROR: Unrecognized RunMode argument '"+runMode+"'.";
			log.log(sMsg);
			throw new IllegalArgumentException(sMsg);
		}

		log.log("Done.");
	}

}
