# Batch Simulator
This program is a "batch simulator", for instructional use in teaching about operating system historical
concepts, specifically 1960's-1970's-style batch-mode computing.

The simulator supports compiling and running programs written in C, Java, or Python, which can optionally read data from an input file at runtime.

Author: Joe Veilleux

## Basic Operation
The batch simulator accepts input "jobs" in the form of text files. These "job files" have to be in a very specific format (details below).
The simulator then processes the job by:
* Creating a "listing" file in the "Output" directory, whose filename is a timestamp, a job sequence number (starts at 1 and increments for each job), followed by the filename of the input file.
* Parsing the file to ensure that it compilies with syntax requirements.
* Copying the program source code and input data (optional) to temporary files
* Compiling/running the program; details differ by language:
  * C: Compile the program using gcc, then execute the program feeding in the input file via I/O redirection.
  * Java: Compile the program to Java bytecode using 'javac', then run the program using 'java', feeding in the input file via I/O redirection.
  * Python: Run the program using the 'python3' command, feeding in the input file via I/O redirection.
* Capturing the output of the compile step (C, Java), and the output of execution, and writing them to the listing file.

To run the batch simulator, do the following:
1. Open a Terminal window and cd to this project's root directory.
2. If necessary, build the project: `mvn clean install`
3. Start the batch simulator: `./runit`
4. Prepare a job file using any standard text editor (NOT a word processor such as MS Word; a simple text editor such as vi, nano, gedit/TextEditor, etc), saving the file in text format (NOT RTF or anthing like that; just text).
5. To "submit" the file to the batch processing system, move/copy it to the Spool/Input directory under this project.
6. The batch simulator will (eventually) notice the input file, process it, create a listing file in the Spool/Output directory corresponding to it, and delete it from the Spool/Input directory.

The batch simulator program continues to run and process additional input files. Where there are no more, it waits. You can terminate it by either:
1. Hitting Ctrl-C in the window where it is running.
2. Creating a file with a special name in the Spool/Input directory: `_BatchSimulator_STOP_`

## Job Syntax

TODO

## Sample Input Files

This section contains sample input job files for each supported language.

TODO
