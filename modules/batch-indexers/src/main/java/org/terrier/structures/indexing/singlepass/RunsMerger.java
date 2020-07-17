/*
 * Terrier - Terabyte Retriever
 * Webpage: http://terrier.org
 * Contact: terrier{a.}dcs.gla.ac.uk
 * University of Glasgow - School of Computing Science
 * http://www.gla.ac.uk/
 *
 * The contents of this file are subject to the Mozilla Public License
 * Version 1.1 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 *
 * The Original Code is RunsMerger.java.
 *
 * The Original Code is Copyright (C) 2004-2020 the University of Glasgow.
 * All Rights Reserved.
 *
 * Contributor(s):
 *   Roi Blanco (rblanc{at}@udc.es)
 *   Craig Macdonald (craigm{at}dcs.gla.ac.uk)
 */
package org.terrier.structures.indexing.singlepass;


import java.io.IOException;
import java.io.Serializable;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.List;
import java.util.ArrayList;

import org.terrier.compression.bit.BitOut;
import org.terrier.compression.bit.BitOutputStream;
import org.terrier.structures.BasicLexiconEntry;
import org.terrier.structures.BitFilePosition;
import org.terrier.structures.FilePosition;
import org.terrier.structures.Pointer;
import org.terrier.structures.postings.Postings;
import org.terrier.structures.postings.IterablePosting;
import org.terrier.structures.LexiconEntry;
import org.terrier.structures.LexiconOutputStream;
import org.terrier.structures.AbstractPostingOutputStream;

/**
 * Merges a set of N runs using a priority queue. Each element of the queue is a {@link org.terrier.structures.indexing.singlepass.RunIterator}
 * each one pointing at a different run in disk. Each run is sorted, so we only need to compare the heads of the 
 * element in the queue in each merging step.
 * As the runs are being merged, they are written (to disk) using a {@link org.terrier.compression.bit.BitOut}. 
 * @author Roi Blanco and Craig Macdonald
 * @since 2.0
  */
class RunsMerger {
	
	/**
	 * Implements a comparator for RunIterators (so it can be used by the queue).
	 * It decides the next reader by the lexicographical order of the terms in the top elements of the readers.
	 * @author Roi Blanco and Craig Macdonald
	 */
	public static class PostingComparator implements Comparator<RunIterator>, Serializable{
		/** generated by eclipse */
		private static final long serialVersionUID = 8674662139960016073L;
		@Override
		public int compare (RunIterator a, RunIterator b)
		{
			int tmp = a.current().getTerm().compareTo(b.current().getTerm());
			return tmp != 0 ? tmp : a.getRunNo() - b.getRunNo(); 
		}
	}
	
	/**
	 * Heap for the postings coming from different runs.
	 * It uses an alphabetical order using the terms, and makes sures runs come in indexing order
	 */	 
	protected Queue<RunIterator> queue;		
	/** file used to write the merged postings to disk*/
	protected AbstractPostingOutputStream pos;	
	
	protected boolean fields = false;
	protected boolean blocks = false;

	/** Number of terms written */
	protected int currentTerm = 0;
	/** Number of pointers written */
	protected int numberOfPointers = 0;
	
	protected RunIteratorFactory runsSource;
	/**
	 * constructor
	 * @param _runsSource
	 */
	public RunsMerger(RunIteratorFactory _runsSource)
	{
		runsSource = _runsSource;
	}
	
	/**
	 * @return the number of terms written.
	 */
	public int getNumberOfTerms(){
		return currentTerm;
	}
	
	/**
	 * @return the number of pointers written.
	 */
	public int getNumberOfPointers(){
		return numberOfPointers;
	}
	
	/** Indicates whether the merging is done or not
	 * @return true if there are no more elements to merge
	 */
	public boolean isDone(){
		return queue.isEmpty();
	}
	
	/**
	 * @return the byte offset in the BitOut (used for lexicon writting)
	 */
	public long getByteOffset(){
		return pos.getOffset().getOffset();
	}
	
	/**
	 * @return the bit offset in the BitOut (used for lexicon writting)
	 */
	public byte getBitOffset(){
		return pos.getOffset().getOffsetBits();
	}
	
	/**
	 * Begins the merge, initilialising the structures.
	 * Notice that the file names must be in order of run-id	
	 * @param size number of runs in disk.
	 * @param fileName String with the file name of the final inverted file.
	 * @throws IOException if an I/O error occurs.
	 */
	protected void init(int size, AbstractPostingOutputStream _pos) throws Exception{
		this.pos = _pos;
		queue = new PriorityQueue<RunIterator>(size, new PostingComparator());
		for(int i = 0; i < size; i++){	
			RunIterator run = runsSource.createRunIterator(i);
			run.next();
			queue.add(run);
		}
	}
	
	/**
	 * Begins the multiway merging phase.
	 * @param size number of runs to be merged.
	 * @param fileName output filename.
	 * @throws Exception if an I/O error occurs. 
	 */
	public void beginMerge(int size, AbstractPostingOutputStream _pos) throws Exception{		
		init(size, _pos);	
	}
	
	/**
	 * Mergers one term in the runs. If a run is exhausted, it is closed and removed from the queue. 
	 * @param lexStream LexiconOutputStream used to write the lexicon.
	 * @throws Exception if an I/O error occurs.
	 */
	public void mergeOne(LexiconOutputStream<String> lexStream) throws Exception {

		// identify the term to process
		RunIterator myRun = queue.poll();
		LexiconEntry termStatistics = myRun.current().getLexiconEntry();
		List<RunIterator> runsWithThisTerm = new ArrayList<>();
		runsWithThisTerm.add(myRun);
		int numEntries = myRun.current().getDf();
		String term = myRun.current().getTerm();
		
		// identify all other runs with this term 
		while(queue.size() > 0 && queue.peek().current().getTerm().equals(term) ) {
			RunIterator run = queue.poll();
			
			// runs must be processed in order
			assert myRun.getRunNo() < run.getRunNo();

			runsWithThisTerm.add(run);
			numEntries += run.current().getDf();
			run.current().addToLexiconEntry(termStatistics);
		}

		// get an IterablePosting represent all of the terms;
		// we use an if to reduce overhead for single run scenarios
		IterablePosting ip = runsWithThisTerm.size() > 1
			? Postings.chain(
				runsWithThisTerm
					.stream()
					.map(run -> run.current().getPostingIterator(0))
					.toArray(IterablePosting[]::new),
				this.blocks, this.fields)
			: myRun.current().getPostingIterator(0);

		// write that posting list to disk, save the pointers and stats in the lexicon
		Pointer p = pos.writePostings(ip);
		termStatistics.setPointer(p);
		termStatistics.setTermId(currentTerm++);
		lexStream.writeNextEntry(term, termStatistics);
		numberOfPointers += numEntries;

		// put the runs back on the priority queue
		for (RunIterator run: runsWithThisTerm) {
			if(run.hasNext()){
				run.next();
				queue.add(run);
			}else{
				run.close();
			}
		}
	}
	
	/**
	 * Ends the merging phase, writes the last entry and closes the streams.
	 * @param lexStream LexiconOutputStream used to write the lexicon.
	 * @throws IOException if an I/O error occurs.	
	 */	
	public void endMerge(LexiconOutputStream<String> lexStream) throws IOException{
		pos.close();
	}	
	
}
