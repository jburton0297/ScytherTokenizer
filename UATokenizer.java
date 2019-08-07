package scyther;
//package edu.uafs.cis.scyther;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import edu.stanford.nlp.ling.CoreAnnotations.LemmaAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokensAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;

public class UATokenizer {

	public static final int TOKEN_BATCH_SIZE = 1000000;
	public static final float TOKEN_BATCH_MAX_PERCENT = 0.9f;
	public static final int MAX_TOKEN_CHAR_LENGTH = 24;
	
	public static HashMap<String, Map.Entry<Integer, Integer>> lexicon;

	public static void main(String[] args) throws Exception {

		lexicon = new HashMap<>();
		
		String inputDir, outputDir = null;
		if(args.length == 2) {
			inputDir = args[0];
			outputDir = args[1];
		} else {
			throw new Exception("Missing required arguments.");
		}

		if(outputDir.charAt(outputDir.length()-1) != '/') outputDir += "/";

		File[] files = new File(inputDir).listFiles();
		BufferedInputStream bis = null;
		PS1Tokenizer tokenizer = null;

		List<Integer> acceptedTokens = Arrays.asList(
			PS1TokenizerConstants.LETTER,
			PS1TokenizerConstants.WORD,
			PS1TokenizerConstants.EMAIL,
			PS1TokenizerConstants.DOMAIN,
			PS1TokenizerConstants.PHONE,
			PS1TokenizerConstants.PRICE
		);
		
		try {
			
			// Setup Stanford NLP library for lemmatization
			Properties props = new Properties();
			props.setProperty("annotators", "tokenize,ssplit,pos,lemma");
			StanfordCoreNLP pipeline = new StanfordCoreNLP(props);
			Annotation annotation;
			List<CoreLabel> tokenLabels;
			

			// Open Google's dictionary file
			Set<String> english = new HashSet<>();
			FileReader englishFr = new FileReader("enable1.txt");
			BufferedReader englishBr = new BufferedReader(englishFr);
			String line;
			while((line = englishBr.readLine()) != null)
				english.add(line.trim());
			englishBr.close();

			int wordTokenCount = 0;
			int frequency = 1;
			int kind = 0;

			// Tokens
			String tokenString;
			Token currentToken;
			
			Token nextToken;
			Map.Entry<String, Integer>[] wordTokenBuffer;
			StringBuilder wordToken;
			int wordTokenBufferIndex = 0;
			
			// Context file
			FileWriter contextFw = new FileWriter(outputDir + "context.txt");
			BufferedWriter contextBw = new BufferedWriter(contextFw);
			
			// Temp files
			FileWriter tempFw = null;
			BufferedWriter tempBw = null;
			
			// Create temp directory
			File tempFolder = new File(outputDir + "temp");
			boolean tempSuccessful;
			if(!tempFolder.exists()) {
				tempSuccessful = tempFolder.mkdir();
				if(!tempSuccessful) {
					//contextBw.close();
					throw new IOException("Failed to create temp directory.");
				}
			}
			
			int fileCount = files.length;
			int currentFileNum = 0;
			
			System.out.println("Tokenizing " + fileCount + " file(s)...\n");
			
			wordTokenBuffer = (Map.Entry<String, Integer>[]) new Map.Entry[TOKEN_BATCH_SIZE];
			for(File file : files) {
				
				currentFileNum++;

				System.out.println("FILE: " + file.getName());
				
				// Open file input stream and init tokenizer
				bis = new BufferedInputStream(new FileInputStream(file));
				tokenizer = new PS1Tokenizer(bis);

				// Tokenize file
				currentToken = tokenizer.getNextToken();
				while(currentToken.kind != PS1TokenizerConstants.EOF) {

					tokenString = currentToken.image;
					kind = currentToken.kind;
					nextToken = tokenizer.getNextToken();

					if(acceptedTokens.contains(currentToken.kind)) {
						
						// Truncate token at max character limit
						if(tokenString.length() > MAX_TOKEN_CHAR_LENGTH)
							tokenString = tokenString.substring(0, MAX_TOKEN_CHAR_LENGTH);

						// Pre-process word tokens
						if(currentToken.kind == PS1TokenizerConstants.WORD || currentToken.kind == PS1TokenizerConstants.LETTER) {

							// Lowercase
							tokenString = tokenString.toLowerCase();

							// Get English words only
							if(english.contains(tokenString)) {
								
								// Store word and file number in word token buffer
								wordTokenBuffer[wordTokenCount++] = new AbstractMap.SimpleEntry<>(tokenString, currentFileNum);
							
							}

						// Store all other tokens in lexicon
						} else {
							frequency = 1;
							if(lexicon.containsKey(tokenString)) {
								frequency = lexicon.get(tokenString).getKey() + 1;
							}

							lexicon.put(tokenString, new AbstractMap.SimpleEntry<Integer, Integer>(frequency, kind));
						}
						
					} // If token is accepted
					
					// Process word token buffer once X percent full
					if(wordTokenCount >= (TOKEN_BATCH_SIZE * TOKEN_BATCH_MAX_PERCENT) || (nextToken.kind == PS1TokenizerConstants.EOF && currentFileNum == fileCount)) {

						if(wordTokenCount >= (TOKEN_BATCH_SIZE * TOKEN_BATCH_MAX_PERCENT))
							System.out.println("\nWord token buffer full.");
						else if(nextToken.kind == PS1TokenizerConstants.EOF)
							System.out.println("\nEnd of corpus reached.");
						
						// Process word token buffer until it is empty
						System.out.println("\nProcessing word tokens...");
						
						wordToken = new StringBuilder();
						for(int i = 0; i < wordTokenBuffer.length; i++) {
							if(wordTokenBuffer[i] != null) wordToken.append(wordTokenBuffer[i].getKey() + " ");
							else break;
						}
						
						// Lemmatize
						System.out.println("Lemmatizing...");

						annotation = new Annotation(wordToken.toString());
						pipeline.annotate(annotation);
						tokenLabels = annotation.get(TokensAnnotation.class);
						CoreLabel l = null;
						
						int currentFile = 0;
						boolean currentFileHasChanged = false;
						System.out.println("word token buffer size=" + wordTokenBuffer.length);
						System.out.println("labels size=" + tokenLabels.size());
						for(int i = 0; i < tokenLabels.size(); i++) {
	
							if(wordTokenBuffer[i] != null) {
							
								// Get lemmatization
								l = tokenLabels.get(i);
								tokenString = l.get(LemmaAnnotation.class);
								
								// Get current file number
								if(currentFile != wordTokenBuffer[i].getValue()) {
									currentFile = wordTokenBuffer[i].getValue();
									System.out.println("Current File: " + currentFile);
									currentFileHasChanged = true;
								}

								// Close old temp bw
								if(currentFileHasChanged && tempBw != null) tempBw.close();	

								// Open temp fw
								if(currentFileHasChanged) {
									tempFw = new FileWriter(outputDir + "temp/temp" + String.format("%010d", currentFile) + ".txt");
									tempBw = new BufferedWriter(tempFw);
									currentFileHasChanged = false;
								}
								
								// Add to lexicon
								frequency = 1;
								if(lexicon.containsKey(tokenString)) {
									frequency = lexicon.get(tokenString).getKey() + 1;
								}

								lexicon.put(tokenString, new AbstractMap.SimpleEntry<Integer, Integer>(frequency, PS1TokenizerConstants.WORD));

								// Write token to context file
								contextBw.write(tokenString + "\n");						
								
								// Write token to temp file
								tempBw.write(tokenString + "\n");

							}							
						}
						
						// Close last temp bw
						if(tempBw != null) tempBw.close();

//						while(wordTokenBufferIndex < TOKEN_BATCH_SIZE) {
//
//							// Read wordTokenBuffer, picking up from where we left off
//							
//							
//							for(int i = wordTokenBufferIndex; i < wordTokenBuffer.length; i++) {
//								if(wordTokenBuffer[i] != null) {
//									
//									// If the current file we're reading from changes, process it before moving on
//									if(currentFile != wordTokenBuffer[i].getValue()) {
//										wordTokenBufferIndex = i;
//										break;
//									} else {
//										wordToken.append(wordTokenBuffer[i].getKey() + " ");
//									}
//									
//								} else {
//									
//									wordTokenBufferIndex = TOKEN_BATCH_SIZE;
//									break;
//
////									// Close temp bw
////									tempBw.close();	
////
////									break wordTokenBufferProcessor;
//								}
//							}
//							
//							
//
//						}

						System.out.println("Finished processing.\n");
						
						// Clear wordTokenBuffer
						wordTokenBuffer = (Map.Entry<String, Integer>[]) new Map.Entry[TOKEN_BATCH_SIZE];
						wordTokenBufferIndex = 0;
						wordTokenCount = 0;
						
						// If the next token is EOF, then exit
						if(nextToken.kind == PS1TokenizerConstants.EOF) {
							//contextBw.write(currentToken + "\n");
							break;
						}		
						
					} // If wordTokenBuffer is full or end of corpus reached
					
					currentToken = nextToken;
					
				} // Tokenizer while loop			
				
				// Close input stream
				bis.close();
				
				System.out.println("UNIQUE TOKENS: " + lexicon.size());
				
			} // For file in files
			
			contextBw.close();
			
			// Tokenizer status
			System.out.println("\nTokenizer finished successfully with " + lexicon.size() + " unique tokens.");
			
			// Write output of tokenizer to files
			//writeTokensToFiles(outputDir);			
			
		} catch (IOException e) {
			e.printStackTrace();
		}
		
	} // Main method
	
	public static void writeTokensToFiles(String outputDir) {
		
		try {
			
			// Loop through lexicon and write to appropriate file
			System.out.println("\nWriting tokens to files...");
			FileWriter freqFw = new FileWriter(outputDir + "frequency.txt");
			BufferedWriter freqBw = new BufferedWriter(freqFw);
			FileWriter emailFw = new FileWriter(outputDir + "email.txt");
			BufferedWriter emailBw = new BufferedWriter(emailFw);
			FileWriter domainFw = new FileWriter(outputDir + "domain.txt");
			BufferedWriter domainBw = new BufferedWriter(domainFw);
			FileWriter wordFw = new FileWriter(outputDir + "word.txt");
			BufferedWriter wordBw = new BufferedWriter(wordFw);
			FileWriter phoneFw = new FileWriter(outputDir + "phone.txt");
			BufferedWriter phoneBw = new BufferedWriter(phoneFw);
			FileWriter priceFw = new FileWriter(outputDir + "price.txt");
			BufferedWriter priceBw = new BufferedWriter(priceFw);
			
			Set<String> keys = lexicon.keySet();
			Iterator<String> it = keys.iterator();
			String key;
			
			String tokenImage;
			int tokenFrequency = 0;
			int tokenKind = 0;
			while(it.hasNext()) {
				key = it.next();
				
				tokenImage = key;
				tokenFrequency = lexicon.get(key).getKey();
				tokenKind = lexicon.get(key).getValue();
			
				if(tokenKind == PS1TokenizerConstants.WORD)
					freqBw.write(tokenImage + "," + tokenFrequency + "\n");

				for(int i = 0; i < tokenFrequency; i++) {
					switch(tokenKind) {
					case PS1TokenizerConstants.EMAIL:
						emailBw.write(tokenImage + "\n");
						break;
					case PS1TokenizerConstants.DOMAIN:
						domainBw.write(tokenImage + "\n");
						break;
					case PS1TokenizerConstants.WORD:
						wordBw.write(tokenImage + "\n");
						break;
					case PS1TokenizerConstants.PHONE:
						phoneBw.write(tokenImage + "\n");
						break;
					case PS1TokenizerConstants.PRICE:
						priceBw.write(tokenImage + "\n");
						break;
					default:
						break;
					}
				}
			}
			
			freqBw.close();
			emailBw.close();
			domainBw.close();
			wordBw.close();
			phoneBw.close();
			priceBw.close();
		
		} catch (IOException e) {
			e.printStackTrace();
		}

		System.out.println("Done writing tokens to files.");

		
	}

}
