/*
 * Bytecode Analysis Framework
 * Copyright (C) 2003,2004 University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs.ba;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.swing.ProgressMonitorInputStream;

import edu.umd.cs.findbugs.SourceLineAnnotation;
import edu.umd.cs.findbugs.SystemProperties;
import edu.umd.cs.findbugs.io.IO;

/**
 * Class to open input streams on source files.
 * It maintains a "source path", which is like a classpath,
 * but for finding source files instead of class files.
 */
public class SourceFinder {
	private static final boolean DEBUG = SystemProperties.getBoolean("srcfinder.debug");
	private static final int CACHE_SIZE = 50;

	/* ----------------------------------------------------------------------
	 * Helper classes
	 * ---------------------------------------------------------------------- */

	/**
	 * Cache of SourceFiles.
	 * We use this to avoid repeatedly having to read
	 * frequently accessed source files.
	 */
	private static class Cache extends LinkedHashMap<String, SourceFile> {
		/**
		 * 
		 */
		private static final long serialVersionUID = 1L;

		@Override
				 protected boolean removeEldestEntry(Map.Entry<String, SourceFile> eldest) {
			return size() >= CACHE_SIZE;
		}
	}

	/**
	 * A repository of source files.
	 */
	private interface SourceRepository {
		public boolean contains(String fileName);

		public boolean isPlatformDependent();

		public SourceFileDataSource getDataSource(String fileName);
	}

	/**
	 * A directory containing source files.
	 */
	private static class DirectorySourceRepository implements SourceRepository {
		private String baseDir;

		public DirectorySourceRepository(String baseDir) {
			this.baseDir = baseDir;
		}

		@Override
		public String toString() {
			return "DirectorySourceRepository:" + baseDir;
		}
		public boolean contains(String fileName) {
			File file = new File(getFullFileName(fileName));
			boolean exists = file.exists();
			if (DEBUG) System.out.println("Exists " + exists + " for " + file);
			return exists;
		}

		public boolean isPlatformDependent() {
			return true;
		}

		public SourceFileDataSource getDataSource(String fileName) {
			return new FileSourceFileDataSource(getFullFileName(fileName));
		}

		private String getFullFileName(String fileName) {
			return baseDir + File.separator + fileName;
		}
	}

	static SourceRepository makeJarURLConnectionSourceRepository(final String url) throws MalformedURLException, IOException {
		final File file = File.createTempFile("jar_cache", null);
		file.deleteOnExit();
		final BlockingSourceRepository r = new BlockingSourceRepository();
		Thread t = new Thread(new Runnable(){

			public void run() {
				try {
				URLConnection connection = new URL(url).openConnection();
				InputStream in = IO.progessMonitoredInputStream(connection, "Loading source via url");
				OutputStream out = new FileOutputStream(file);
				IO.copy(in, out);
				in.close();
				out.close();
				r.setBase(new ZipSourceRepository(new ZipFile(file)));
				} catch (IOException e) {
					// TODO: Figure out what to do here;
				}
            }}, "Source loading thread");
		t.setDaemon(true);
		t.start();
		return r;
	}
		
	static class BlockingSourceRepository implements SourceRepository {
		SourceRepository base;
		final CountDownLatch ready = new CountDownLatch(1);
		public BlockingSourceRepository() {
        }
		
		public boolean isReady() {
			return ready.getCount() == 0;
		}
		public void setBase(SourceRepository base) {
			this.base = base;
			ready.countDown();
		}
		private void await() {
			try {
	            ready.await();
            } catch (InterruptedException e) {
	            throw new IllegalStateException("Unexpected interrupt", e);
            }
		}
		public boolean contains(String fileName) {
			await();
	        return base.contains(fileName);
        }
		public SourceFileDataSource getDataSource(String fileName) {
			await();
	        return base.getDataSource(fileName);
        }
		public boolean isPlatformDependent() {
			await();
	        return base.isPlatformDependent();
        }
		

		
	}
	/**
	 * A zip or jar archive containing source files.
	 */
	 static class ZipSourceRepository implements SourceRepository {
		 ZipFile zipFile;

		public ZipSourceRepository(ZipFile zipFile) {
			this.zipFile = zipFile;
		}

		public boolean contains(String fileName) {
			return zipFile.getEntry(fileName) != null;
		}

		public boolean isPlatformDependent() {
			return false;
		}

		public SourceFileDataSource getDataSource(String fileName) {
			return new ZipSourceFileDataSource(zipFile, fileName);
		}
	}

	/* ----------------------------------------------------------------------
	 * Fields
	 * ---------------------------------------------------------------------- */

	private List<SourceRepository> repositoryList;
	private Cache cache;

	/* ----------------------------------------------------------------------
	 * Public methods
	 * ---------------------------------------------------------------------- */

	/**
	 * Constructor.
	 */
	public SourceFinder() {
		if (DEBUG) System.out.println("Debugging SourceFinder");
		repositoryList = new LinkedList<SourceRepository>();
		cache = new Cache();
	}

	/**
	 * Set the list of source directories.
	 */
	public void setSourceBaseList(List<String> sourceBaseList) {
		for (String repos : sourceBaseList) {
			if (repos.endsWith(".zip") || repos.endsWith(".jar")) {
				// Zip or jar archive
				try {
					if (repos.startsWith("http:") || repos.startsWith("https:") || repos.startsWith("file:")) 
						repositoryList.add(makeJarURLConnectionSourceRepository(repos));
					else 
						repositoryList.add(new ZipSourceRepository(new ZipFile(repos)));
				} catch (IOException e) {
					// Ignored - we won't use this archive
					e.printStackTrace();
				}
			} else {
				// Directory
				repositoryList.add(new DirectorySourceRepository(repos));
			}
		}
	}

	/**
	 * Open an input stream on a source file in given package.
	 *
	 * @param packageName the name of the package containing the class whose source file is given
	 * @param fileName    the unqualified name of the source file
	 * @return an InputStream on the source file
	 * @throws IOException if a matching source file cannot be found
	 */
	public InputStream openSource(String packageName, String fileName) throws IOException {
		SourceFile sourceFile = findSourceFile(packageName, fileName);
		return sourceFile.getInputStream();
	}
	public InputStream openSource(SourceLineAnnotation source) throws IOException {
		SourceFile sourceFile = findSourceFile(source);
		return sourceFile.getInputStream();
	}
	public SourceFile findSourceFile(SourceLineAnnotation source) throws IOException {
		if (source.isSourceFileKnown())
			return findSourceFile(source.getPackageName(), source.getSourceFile());
		String packageName = source.getPackageName();
		String baseClassName = source.getClassName();
		int i = baseClassName.lastIndexOf('.');
		baseClassName = baseClassName.substring(i+1);
		int j = baseClassName.indexOf("$");
		if (j >= 0)
			baseClassName = baseClassName.substring(0,j);
		return findSourceFile(packageName, baseClassName + ".java");

	}
	/**
	 * Open a source file in given package.
	 *
	 * @param packageName the name of the package containing the class whose source file is given
	 * @param fileName    the unqualified name of the source file
	 * @return the source file
	 * @throws IOException if a matching source file cannot be found
	 */
	public SourceFile findSourceFile(String packageName, String fileName) throws IOException {
		// On windows the fileName specification is different between a file in a directory tree, and a 
		// file in a zip file. In a directory tree the separator used is '\', while in a zip it's '/'
		// Therefore for each repository figure out what kind it is and use the appropriate separator.

		// In all practicality, this code could just use the hardcoded '/' char, as windows can open 
		// files with this separator, but to allow for the mythical 'other' platform that uses an
		// alternate separator, make a distinction

		// Create a fully qualified source filename using the package name for both directories and zips
		String platformName = packageName.replace('.', File.separatorChar) + 
								(packageName.length() > 0 ? File.separator : "") + fileName;
		String canonicalName = packageName.replace('.', '/') + 
								(packageName.length() > 0 ? "/" : "") + fileName;

		// Is the file in the cache already? Always cache it with the canonical name
		SourceFile sourceFile = cache.get(canonicalName);
		if (sourceFile != null)
			return sourceFile;

		// Find this source file, add its data to the cache
		if (DEBUG) System.out.println("Trying " + fileName +  " in package " + packageName + "...");
		// Query each element of the source path to find the requested source file
		for (SourceRepository repos : repositoryList) {
			if (repos instanceof BlockingSourceRepository && !((BlockingSourceRepository)repos).isReady())
				continue;
			fileName = repos.isPlatformDependent() ? platformName : canonicalName;
			if (DEBUG) System.out.println("Looking in " + repos  + " for " + fileName);
			if (repos.contains(fileName)) {
				// Found it
				sourceFile = new SourceFile(repos.getDataSource(fileName));
				cache.put(canonicalName, sourceFile); // always cache with canonicalName
				return sourceFile;
			}
		}

		throw new FileNotFoundException("Can't find source file " + fileName);
	}
}

// vim:ts=4
