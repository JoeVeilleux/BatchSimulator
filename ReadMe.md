# Batch Simulator
This program is a "batch simulator", for instructional use in teaching about operating system historical
concepts, specifically 1960's-1970's-style batch-mode computing.

The simulator supports compiling and running programs written in C, Java, or Python, which can optionally read data from an input file at runtime.

This simulator has been used at Simmons University in course CS-245 (Computing Systems), in the introductory section on Operating System History.
The students are given background information (similar to the information shown below) and asked to create a "card deck" (i.e. a text file) and
submit it to the batch-processing system, then wait for the output and examine it to ensure that their attempt was successful.

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

A job's input file must be in this format:

<table border="1">
<tr><th>Name</th><th>Description</th></tr>
<tr><td>$JOB card</td><td>The first line: $JOB, followed by a description of this job</td></tr>
<tr><td>"Compile" card</td><td>
The second line tells the batch system that the next lines are a program, either in Python,<br>
Java, or C. This card contains the token "$PY", "$JAVA", or "$C" followed by the name of <br>
your program (in the case of Java, this must be the Java program’s main class).</td></tr>
<tr><td>Program</td><td>Your program, in either Java, C, or Python, including all import statements (if needed), the<br>
actual Java or C or Python code, etc.</td></tr>
<tr><td>$RUN card</td><td>The $RUN card immediately follows the program lines, and instructs the batch system to <br>
run your program using the input data which follows.</td></tr>
<tr><td>Input data</td><td>Any lines after $RUN and before $END are input data. This data is fed in to your program.</td></tr>
<tr><td>$END card</td><td>Your "card deck" ends with "$END" on a line by itself.</td></tr>
</table>

## Sample Input Files

This section contains sample input job files for each supported language.

### Java

```
$JOB Joe's First test job to be run under the BatchSimulator
$JAVA Foo
public class Foo {
    public static void main(String[] args) {
        System.out.println("Hello, world!");
    }
}
$RUN
$END
```

### C

```
$JOB Joe's First C program to be run under the BatchSimulator
#include <stdio.h>
$C Hello
int main() {
    printf("Hello, world!\n");
}
$RUN
$END
```

### Python

```
$JOB Joe's First Python program to be run under the BatchSimulator
$PY Hello
print("Hello, world!");
$RUN
$END
```

Note that in these examples, the program does not read any data from the input stream, so there are no lines between $RUN and $END.

### Example With Input

This example program, in C, reads from the input "cards" which are supplied between $RUN and $END (similar for other languages):

```
$JOB Joe's First C program to be run under the BatchSimulator
$C Hello
#include <stdio.h>
char buf[20];
int main() {
    printf("Hello, world!\n");
    int i;
    for (i = 0; i < 2; i++) {
        fgets(buf, 20, stdin);
        printf("buf='%s'\n", buf);
    }
}
$RUN
This is a test
This is only a test
$END
```
