      *****************************************************************
      * Test-harness stand-in for the LE service CEE3ABD (user abend).
      * Displays the abend code, sets RETURN-CODE and stops the run
      * (files are closed by STOP RUN, as VSAM keeps written records).
      *****************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CEE3ABD.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-CODE                 PIC 9(04).
       LINKAGE SECTION.
       01  LK-ABCODE               PIC S9(9) BINARY.
       01  LK-TIMING               PIC S9(9) BINARY.
       PROCEDURE DIVISION USING LK-ABCODE LK-TIMING.
           MOVE LK-ABCODE TO WS-CODE
           DISPLAY 'CEE3ABD ABEND U' WS-CODE
           MOVE LK-ABCODE TO RETURN-CODE
           STOP RUN.
