package com.joev.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

public class SimpleLogger {

	private static final String sNL = System.getProperty("line.separator");
	
	public enum LogFormat { WithTag, Raw, Banner, WithLineNumbers };
	
	private String sTag = null;
	private File fOutputFile = null;
	
	public SimpleLogger(String sTag) {
		this.sTag = sTag;
	}
	
	public void log(String sMsg) {
		this.log(sMsg, LogFormat.WithTag);
	}
	
	public void log(String sMsg, LogFormat lf) {
		// First split it into lines and perform any requested formatting...
		ArrayList<String> alsMsg = new ArrayList<String>();
		if (lf.equals(LogFormat.Banner))
			alsMsg.add("***"+"********************************************************************************"+"***");
		int iLineNo = 0;
		for (String sLine : sMsg.split("[\r\n]")) {
			iLineNo++;
			switch (lf) {
			case WithTag:
				sLine = sTag+": "+sLine;
				break;
			case Raw:
				break;
			case Banner:
				sLine = String.format("** %-80.80s **", sLine);
				break;
			case WithLineNumbers:
				sLine = String.format("%04d %s", iLineNo, sLine);
				break;
			}
			alsMsg.add(sLine);
		}
		if (lf.equals(LogFormat.Banner))
			alsMsg.add("***"+"********************************************************************************"+"***");
		// First, log it to STDOUT...
		for (String sLine : alsMsg) {
			System.out.println(sLine);
		}
		// If an output log file is set, also write it to the log file...
		if (this.fOutputFile != null) {
			try {
				FileWriter fw = new FileWriter(this.fOutputFile, true);
				for (String sLine : alsMsg) {
					fw.write(sLine);
					fw.write(sNL);
				}
				fw.close();
			} catch (IOException e) {
				System.err.println("IOException trying to write to log file '"+this.fOutputFile.getAbsolutePath()+"!");
			}
		}
	}
	
	public void setLogFile(File fOutputFile) {
		this.fOutputFile = fOutputFile;
		if (this.fOutputFile != null) {
			// Delete the file, if it previously existed...
			if (this.fOutputFile.exists())
				fOutputFile.delete();
		}
	}
	
	public void logWithLineNumbers(String sSectionIndent, String sSectionTag, String sData) {
		if (sData.length() == 0) {
			log(sSectionIndent + sSectionTag + ": (empty)");
		} else {
			log(sSectionIndent + sSectionTag + ":");
			log(sSectionIndent + "  Line Contents");
			log(sSectionIndent + "  ==== ================================================================================");
			int iLineNo = 1;
			for (String sLine : sData.split("[\r\n]+")) {
				log(String.format("%s  %04d %s", sSectionIndent, iLineNo, sLine));
				iLineNo++;
			}
		}
	}


	public static void main(String[] args) {
		SimpleLogger log = new SimpleLogger(SimpleLogger.class.getSimpleName());
		log.log("Test message using default format (tagged)");
		log.setLogFile(new File("SimpleLoggerTestOutputFile.log"));
		log.log("This test message should be written to STDOUT and to the log file.");
		log.log("Another message for both the log file and STDOUT");
		log.log("This should be raw output, not adorned with the 'tag'...", LogFormat.Raw);
		log.log("This\nOutput\nis\nBannerized!", LogFormat.Banner);
		log.setLogFile(null);
		log.log("This message should NOT go to the log file.");
		log.log("This should be raw output, not adorned with the 'tag'...", LogFormat.Raw);
		log.log("This\nOutput\nis\nBannerized!", LogFormat.Banner);
	}

}
