Regenerate the test data (needs only python3):
  python3 gen_spoken.py 7   -> spoken_cases.tsv   (change the number for a fresh random set)
  python3 gen_ocr.py 5      -> ocr_cases.tsv
Copy the .tsv files to app/src/test/resources/ and run ParserCorpusTest.
