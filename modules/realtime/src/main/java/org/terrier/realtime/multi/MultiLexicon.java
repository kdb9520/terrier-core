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
 * The Original Code is MultiLexicon.java.
 *
 * The Original Code is Copyright (C) 2004-2019 the University of Glasgow.
 * All Rights Reserved.
 *
 * Contributor(s):
 *   Richard McCreadie <richard.mccreadie@glasgow.ac.uk>
 *   Stuart Mackie <s.mackie.1@research.gla.ac.uk>
 */

package org.terrier.realtime.multi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.collections4.map.LRUMap;
import org.apache.commons.lang3.tuple.Pair;
import org.terrier.structures.Lexicon;
import org.terrier.structures.LexiconEntry;
import org.terrier.utility.ApplicationSetup;
import org.terrier.utility.StaTools;

/**
 * A Lexicon index structure for use with a MultiIndex. It wraps around multiple lexicons
 * from different index shards. 
 * 
 * IMPORTANT: Not all lexicon access methods are supported since a lexicon entry can appear 
 * in any number of lexicons!
 * 
 * This has the following consequences: 
 *  <ul><li>A MultiLexicon can not be iterated over without doing a temporary merge of all lexicon structures, hence we do not support this.</li>
 * <li>getIthLexiconEntry() is not supported (the contents of the ith entry can change over time as new documents are added) </li>
 * <li>The unique number of terms is not stored and needs to be calculated on-the-fly. </li>
 * </ul>
 * 
 * <p><b>Properties</b></p>
 * <ul>
 * <li><tt>MultiLexicon.approxNumEntries</tt> - do we try and approximate the number of lexicon entries (saves a lot of time but is inaccurate), default is true.</li>
 * </ul>
 * 
 * @author Richard McCreadie, Stuart Mackie
 * @since 4.0
 */
public class MultiLexicon extends Lexicon<String> {

	LRUMap<Integer, String> hash2term = new LRUMap<>(1000);
	private Lexicon<String>[] lexicons;
	private int[] numTerms;
	private ArrayList<String> uniqueTerms;

	private boolean approximateNumberofEntries = Boolean
			.parseBoolean(ApplicationSetup.getProperty(
					"MultiLexicon.approxNumEntries", "true"));

	/**
	 * constructor.
	 */
	public MultiLexicon(Lexicon<String>[] lexicons, int[] numTerms) {
		this.lexicons = lexicons;
		this.numTerms = numTerms;
		Set<String> unorderedTerms = new HashSet<String>();
		if (!approximateNumberofEntries)
			for (Lexicon<String> lex : lexicons)
				for (int t = 0; t < lex.numberOfEntries(); t++)
					unorderedTerms.add(lex.getIthLexiconEntry(t).getKey());
		uniqueTerms = new ArrayList<String>(unorderedTerms.size());
		for (String t : unorderedTerms)
			uniqueTerms.add(t);
		Collections.sort(uniqueTerms);

	}

	public Lexicon<String> getIthLexicon(int index) {
		return lexicons[index];
	}
	
	public static int hashCode(String term) {
		int hash = term.hashCode();
		//System.err.println(hash);
		char first= term.charAt(0);
		byte prefix = 0;
		if (first <= '~')
			prefix = (byte)first;
		//System.err.println("prefix of "+term+ " is " +  prefix);
		return (hash & 0xFF00) | prefix; 
	}
	
	public static char hashCodePrefix(int hashcode) {
		byte prefix = (byte) (hashcode & 0x00FF);
		//System.err.println("prefix of "+hashcode+ "is " +  prefix);
		return (char)prefix;
	}
	
	
	/** {@inheritDoc} */
	public int numberOfEntries() {
		if (approximateNumberofEntries)
			return StaTools.max(numTerms);
		else
			return uniqueTerms.size();
	}

	/** {@inheritDoc} */
	public LexiconEntry getLexiconEntry(String term) {
		LexiconEntry[] les = new LexiconEntry[lexicons.length];
		LexiconEntry le;
		int i = 0;
		boolean found = false;
		for (Lexicon<String> lexicon : lexicons) {
			le = lexicon.getLexiconEntry(term);
			if (le != null) {
				les[i] = le;
				found = true;
			}
			i++;
		}
		if (! found)
			return null;
		int hashcode = hashCode(term);
		this.hash2term.putIfAbsent(hashcode, term);
		return new MultiLexiconEntry(les, hashcode);
	}
	
	int computeGlobalTermIdFromLocal(int localtermid, int shard) {
		String term = lexicons[shard].getLexiconEntry(localtermid).getKey();
		int hashcode = hashCode(term);
		this.hash2term.putIfAbsent(hashcode, term);
		return hashcode;
	}

	/** {@inheritDoc} */
	public Entry<String, LexiconEntry> getLexiconEntry(int termid) {
		
		//global termid -> String
		String t = globalTermId2Term(termid);
		if (t == null)
			return null;
		
		return Pair.of(t,getLexiconEntry(t));
	}
	
	String globalTermId2Term(int hashcode) {
		
		String rtr = this.hash2term.get(hashcode);
		if (rtr != null)
			return rtr;
		
		char prefix = hashCodePrefix(hashcode);
		for(Lexicon<String> lex : lexicons)
		{
			Iterator<Entry<String,LexiconEntry>> iter = lex.getLexiconEntryRange(String.valueOf(prefix), String.valueOf(prefix+1));
			while(iter.hasNext())
			{
				Entry<String,LexiconEntry> lee = iter.next();
				if (hashCode(lee.getKey()) == hashcode)
				{
					hash2term.put(hashcode, lee.getKey());
					return lee.getKey();
				}
			}
		}
		return null;
	}
	
	/** This is an extra lexicon entry method for fast lookups of LexiconEntry's
	 * by term id. */
//	public Entry<String, LexiconEntry> getLexiconEntry(int termid, int shard) {
//		//System.err.println("Looking up "+termid+" in shard "+shard+" (contains="+numTerms[shard]+")");
//		String term = lexicons[shard].getLexiconEntry(termid).getKey();
//		return new org.terrier.structures.collections.MapEntry<String, LexiconEntry>(
//				term, getLexiconEntry(term));
//	}


	/** This is an invalid method since a lexicon entry can appear in any number of
	 * lexicons. In general DO NOT USE THIS! This method is only implemented
	 * such that a random term can be chosen within the JUnit tests.*/
	public Entry<String, LexiconEntry> getIthLexiconEntry(int index) {
		return getLexiconEntry(index);
	}

	/** Not implemented. */
	public Iterator<Entry<String, LexiconEntry>> iterator() {
		return null;
	}

	/** Not implemented. */
	public void close() throws IOException {
	}

	@Override
	public Iterator<Entry<String, LexiconEntry>> getLexiconEntryRange(
			String from, String to) {
		// TODO This has not been implemented - craig probably need to look at this to see how it should work
		return null;
	}

}
