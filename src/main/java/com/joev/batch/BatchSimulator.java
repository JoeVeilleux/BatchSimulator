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
	
	private static final boolean bVerbose = false;

	private static final HashMap<String,File> alfSpoolDir = new HashMap<String,File>();
	private static final SimpleDateFormat sdfLastMod = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	private static final SimpleDateFormat sdfRunDTOutputFilename = new SimpleDateFormat("yyyyMMddHHmmssSSS");
	
	// Time (seconds) to wait after each job completes
	private static final int iWaitAfterEachJob = 10;
	// Time (seconds) to wait for new work to come in when input queue is empty...
	private static final int iWaitForWork = 10; 

	enum RunMode { SingleThreadAllFiles, SingleThreadWaitForStop }
	
	private enum PARSETOKEN {
		JOB("^\\$JOB (.*)"), JAVA("^\\$JAVA (\\w+)"), JAVACODEORRUN("^\\$RUN"), INPUTDATAOREND("^\\$END"), ERROREXIT("");
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
	private static ArrayList<BatchJob> parseBatchJobStream(File fInput) throws Exception {
		ArrayList<BatchJob> albj = new ArrayList<BatchJob>();
		
		// Read and parse the input stream, which is expected to consist of:
		// $JOB <comments>
		// $JAVA <MainClass>
		//   ...code of a Java program...
		// $RUN
		//   ...input data for the Java program...
		// $END
		// ...and possibly additional JOBs...
		BufferedReader brInputJobs = null;
		log.log("Reading input from file '"+fInput.getAbsolutePath()+"'...");
		brInputJobs = new BufferedReader(new FileReader(fInput));
		PARSETOKEN ptExpect = PARSETOKEN.JOB;
		StringBuffer sbJob = new StringBuffer();
		String sJobComments = null;
		StringBuffer sbJavaCode = new StringBuffer();
		StringBuffer sbInputData = new StringBuffer();
		String sJavaMainClass = null;

		String sLine = null;
		int iLine = 0;
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
					ptExpect = PARSETOKEN.JAVA;
				} else {
					log.log("ERROR: Input line #"+iLine+" '"+sLine+"' does not match expected pattern '"+ptExpect.pat.pattern()+"'");
					ptExpect = PARSETOKEN.ERROREXIT;
				}
				break;
			case JAVA:
				if (m.matches()) {
					sJavaMainClass = m.group(1);
					if (bVerbose) log.log("PARSER: Recognized '$JAVA'. MainClass='"+sJavaMainClass+"'");
					ptExpect = PARSETOKEN.JAVACODEORRUN;
				} else {
					log.log("ERROR: Input line #"+iLine+" '"+sLine+"' does not match expected pattern '"+ptExpect.pat.pattern()+"'");
					ptExpect = PARSETOKEN.ERROREXIT;
				}
				break;
			case JAVACODEORRUN:
				if (m.matches()) {
					if (bVerbose) log.log("PARSER: Recognized '$RUN'.");
					ptExpect = PARSETOKEN.INPUTDATAOREND;
				} else {
					// It must be a (another?) line of Java code...
					if (sbJavaCode.length() > 0)
						sbJavaCode.append(sNL);
					sbJavaCode.append(sLine);
				}
				break;
			case INPUTDATAOREND:
				if (m.matches()) {
					if (bVerbose) log.log("PARSER: Recognized '$END'.");
					// Queue up the now-completed job...
					BatchJob bj = new BatchJob(fInput.getName(), iJobNumber, sbJob.toString(), sJobComments, sJavaMainClass, sbJavaCode.toString(), sbInputData.toString());
					albj.add(bj);
					// Reset for the next job...
					iJobNumber++;
					sbJob = new StringBuffer();
					sJobComments = null;
					sJavaMainClass = null;
					sbJavaCode = new StringBuffer();
					sbInputData = new StringBuffer();
					// We expect to see another '$JOB' token next (or the end of the input stream)...
					ptExpect = PARSETOKEN.JOB;
				} else {
					// It must be a (another?) line of input data...
					if (sbInputData.length() > 0)
						sbInputData.append(sNL);
					sbInputData.append(sLine);
				}
				break;
			default:
				log.log("ERROR: Unexpected value '"+ptExpect+"' for parse-status flag!");
				ptExpect = PARSETOKEN.ERROREXIT;
				break;
			}
			// If we hit an error, then exit...
			if (ptExpect.equals(PARSETOKEN.ERROREXIT)) {
				log.log("Exiting parse at line "+iLine+" due to error.");
				break;
			}
		}
		brInputJobs.close();
		
		log.log("At end of input loop:");
		log.log("Number of lines read: "+iLine);

		
		return albj;
	}
	
	private static class BatchJob implements Runnable {
		private SimpleLogger log = null;
		private String sFilename;
		private int iJobNumber;
		private String sJob;
		private String sJobComments;
		private String sJavaMainClass;
		private String sJavaCode;
		private String sInputData;
		private Date dtRun = null;
		private CommandRunnerResult crrCompile = null;
		private CommandRunnerResult crrRun = null;
		
		BatchJob(String sFilename, int iJobNumber, String sJob, String sJobComments, String sJavaMainClass, String sJavaCode, String sInputData) {
			this.sFilename = sFilename;
			this.iJobNumber = iJobNumber;
			this.sJob = sJob;
			this.sJobComments = sJobComments;
			this.sJavaMainClass = sJavaMainClass;
			this.sJavaCode = sJavaCode;
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
			
			int iRC = 0;
			CommandRunner cmdRunner = new CommandRunner();
			
			log.log("Java program code");
			log.log(this.sJavaCode, LogFormat.WithLineNumbers);
			log.log("Input data");
			log.log(this.sInputData, LogFormat.WithLineNumbers);
			
			// Create a temporary directory, and put the Java and input-data files into it...
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
					File fProgram = new File(fTmpDir, sJavaMainClass+".java");
					FileWriter fwProgram;
					fwProgram = new FileWriter(fProgram);
					fwProgram.write(sJavaCode);
					fwProgram.close();
					log.log("Wrote Java program to: '"+fProgram.getAbsolutePath()+"'.");
					File fInputData = new File(fTmpDir, "INPUTDATA.txt");
					FileWriter fwInputData = new FileWriter(fInputData);
					fwInputData.write(sInputData);
					fwInputData.close();
					log.log("Wrote input data to: '"+fInputData.getAbsolutePath()+"'.");
					
					// Compile the program...
					log.log("Compiling program...");
					this.crrCompile = cmdRunner.runCommand("javac "+sJavaMainClass+".java", fTmpDir);
					log.log("Compilation command result:");
					log.log(this.crrCompile.toString());
					if (crrCompile.iRC == 0) {
						log.log("Compilation successful!");
					} else {
						log.log("Compilation unsuccessful!");
					}
					iRC = this.crrCompile.iRC;
					
					// Execute the program (but only if it compiled cleanly)...
					if (iRC == 0) {
						log.log("Executing program...");
						this.crrRun = cmdRunner.runCommand("java "+sJavaMainClass+" < "+fInputData.getName(), fTmpDir);
						log.log("Program execution result:");
						log.log(this.crrRun.toString());
						iRC = this.crrRun.iRC;
						if (crrCompile.iRC == 0) {
							log.log("Program execution successful!");
						} else {
							log.log("Program execution unsuccessful!");
						}
					}
					
					// Clean up...
					log.log("Cleaning up...");
					new File(fTmpDir, sJavaMainClass+".class").delete();
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
				ArrayList<BatchJob> albj = parseBatchJobStream(f);
				for (BatchJob bj : albj) {
					bj.run();
					waitBetweenJobs();
				}
				log.log("Done with BatchJob file: "+f.getAbsolutePath()+". Deleting it...");
				log.log("Deleting BatchJob file: "+f.getAbsolutePath()+"...");
				log.log(f.delete() ? "File deleted successfully." : "Error deleting file. Unable to delete!");
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
						ArrayList<BatchJob> albj = parseBatchJobStream(f);
						for (BatchJob bj : albj) {
							bj.run();
							waitBetweenJobs();
						}
						log.log("Done with BatchJob file: "+f.getAbsolutePath()+". Deleting it...");
						log.log("Deleting BatchJob file: "+f.getAbsolutePath()+"...");
						log.log(f.delete() ? "File deleted successfully." : "Error deleting file. Unable to delete!");
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
