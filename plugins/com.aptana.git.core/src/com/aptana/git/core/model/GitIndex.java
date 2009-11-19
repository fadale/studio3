package com.aptana.git.core.model;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.osgi.util.NLS;

import com.aptana.git.core.GitPlugin;
import com.aptana.util.ProcessUtil;

public class GitIndex
{

	/**
	 * File extensions we check against and use to assume if a file may be binary (to not show a diff/content in various UI views)
	 */
	private static final String[] BINARY_EXTENSIONS = new String[] { ".pdf", ".jpg", ".jpeg", ".png", ".bmp", ".gif", ".o", ".class", ".zip", ".gz", ".tar", ".ico", ".so", ".jar" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$ //$NON-NLS-11$ //$NON-NLS-12$ //$NON-NLS-13$ //$NON-NLS-14$
	
	private GitRepository repository;
	private boolean amend;
	private String workingDirectory;
	private List<ChangedFile> files;

	private int refreshStatus = 0;
	private boolean notify;
	private Map<String, String> amendEnvironment;

	GitIndex(GitRepository repository, String workingDirectory)
	{
		super();

		Assert.isNotNull(repository, "GitIndex requires a repository"); //$NON-NLS-1$
		Assert.isNotNull(workingDirectory, "GitIndex requires a working directory"); //$NON-NLS-1$

		this.repository = repository;
		this.workingDirectory = workingDirectory;
		this.files = new Vector<ChangedFile>();
	}

	public void refresh()
	{
		refresh(true);
	}

	synchronized void refresh(boolean notify)
	{
		this.notify = notify;
		refreshStatus = 0;

		Map<Integer, String> result = GitExecutable.instance().runInBackground(workingDirectory, "update-index", "-q", //$NON-NLS-1$ //$NON-NLS-2$
				"--unmerged", "--ignore-missing", "--refresh"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

		int exitValue = result.keySet().iterator().next();
		if (exitValue != 0)
			return;

		Set<Job> jobs = new HashSet<Job>();
		jobs.add(new Job("other files") //$NON-NLS-1$
		{

			@Override
			protected IStatus run(IProgressMonitor monitor)
			{
				String output = GitExecutable.instance().outputForCommand(workingDirectory, "ls-files", "--others", //$NON-NLS-1$ //$NON-NLS-2$
						"--exclude-standard", "-z"); //$NON-NLS-1$ //$NON-NLS-2$
				readOtherFiles(output);
				return Status.OK_STATUS;
			}
		});
		jobs.add(new Job("unstaged files") //$NON-NLS-1$
		{

			@Override
			protected IStatus run(IProgressMonitor monitor)
			{
				String output = GitExecutable.instance().outputForCommand(workingDirectory, "diff-files", "-z"); //$NON-NLS-1$ //$NON-NLS-2$
				readUnstagedFiles(output);
				return Status.OK_STATUS;
			}
		});
		jobs.add(new Job("staged files") //$NON-NLS-1$
		{

			@Override
			protected IStatus run(IProgressMonitor monitor)
			{
				String output = GitExecutable.instance().outputForCommand(workingDirectory, "diff-index", "--cached", //$NON-NLS-1$ //$NON-NLS-2$
						"-z", getParentTree()); //$NON-NLS-1$
				readStagedFiles(output);
				return Status.OK_STATUS;
			}
		});

		this.files.clear(); // FIXME Is this right? Seems like after we commit we leave some files in memory that
		// shouldn't be there anymore (especially unstaged ones)

		// Schedule all the jobs
		for (Job toSchedule : jobs)
		{
			refreshStatus++;
			toSchedule.setSystem(true);
			toSchedule.setPriority(Job.SHORT);
			toSchedule.schedule();
		}
		// Now wait for them to finish
		for (Job toJoin : jobs)
		{
			try
			{
				toJoin.join();
			}
			catch (InterruptedException e)
			{
				// ignore
			}
		}
	}

	private String getParentTree()
	{
		String parent = amend ? "HEAD^" : "HEAD"; //$NON-NLS-1$ //$NON-NLS-2$

		if (repository.parseReference(parent) == null)
			// We don't have a head ref. Return the empty tree.
			return "4b825dc642cb6eb9a060e54bf8d69288fbee4904"; //$NON-NLS-1$

		return parent;
	}

	void setAmend(boolean amend)
	{
		if (this.amend == amend)
			return;
		this.amend = amend;
		this.amendEnvironment = null;

		refresh();
		if (!amend)
			return;

		// If we amend, we want to keep the author information for the previous commit
		// We do this by reading in the previous commit, and storing the information
		// in a dictionary. This dictionary will then later be read by commit()
		String message = GitExecutable.instance().outputForCommand("cat-file commit HEAD"); //$NON-NLS-1$
		Pattern p = Pattern.compile("\nauthor ([^\n]*) <([^\n>]*)> ([0-9]+[^\n]*)\n"); //$NON-NLS-1$
		Matcher m = p.matcher(message);
		if (m.find())
		{
			amendEnvironment = new HashMap<String, String>();
			amendEnvironment.put(GitEnv.GIT_AUTHOR_NAME, m.group(1));
			amendEnvironment.put(GitEnv.GIT_AUTHOR_EMAIL, m.group(2));
			amendEnvironment.put(GitEnv.GIT_AUTHOR_DATE, m.group(3));
		}
	}

	private void readOtherFiles(String string)
	{
		List<String> lines = linesFromNotification(string);
		Map<String, List<String>> dictionary = new HashMap<String, List<String>>(lines.size());
		// Other files are untracked, so we don't have any real index information. Instead, we can just fake it.
		// The line below is not used at all, as for these files the commitBlob isn't set
		List<String> fileStatus = new ArrayList<String>();
		fileStatus.add(":000000"); // for new file //$NON-NLS-1$
		fileStatus.add("100644"); //$NON-NLS-1$
		fileStatus.add("0000000000000000000000000000000000000000"); // SHA //$NON-NLS-1$
		fileStatus.add("0000000000000000000000000000000000000000"); //$NON-NLS-1$
		fileStatus.add("A"); // A for Add, D for delete //$NON-NLS-1$
		fileStatus.add(null);
		for (String path : lines)
		{
			if (path.length() == 0)
				continue;
			dictionary.put(path, fileStatus);
		}

		addFilesFromDictionary(dictionary, false, false);
		indexStepComplete();
	}

	private void readStagedFiles(String string)
	{
		List<String> lines = linesFromNotification(string);
		Map<String, List<String>> dic = dictionaryForLines(lines);
		addFilesFromDictionary(dic, true, true);
		indexStepComplete();
	}

	private void readUnstagedFiles(String string)
	{
		List<String> lines = linesFromNotification(string);
		Map<String, List<String>> dic = dictionaryForLines(lines);
		addFilesFromDictionary(dic, false, true);
		indexStepComplete();
	}

	List<String> linesFromNotification(String string)
	{
		// FIXME: throw an error?
		if (string == null)
			return Collections.emptyList();

		// Strip trailing null
		if (string.endsWith("\0")) //$NON-NLS-1$
			string = string.substring(0, string.length() - 1);

		if (string.length() == 0)
			return Collections.emptyList();

		return StringUtil.componentsSeparatedByString(string, "\0"); //$NON-NLS-1$
	}

	private Map<String, List<String>> dictionaryForLines(List<String> lines)
	{
		Map<String, List<String>> dictionary = new HashMap<String, List<String>>(lines.size() / 2);

		// Fill the dictionary with the new information. These lines are in the form of:
		// :00000 :0644 OTHER INDEX INFORMATION
		// Filename
		Assert.isTrue(lines.size() % 2 == 0, "Lines must have an even number of lines: " + lines); //$NON-NLS-1$
		Iterator<String> iter = lines.iterator();
		while (iter.hasNext())
		{
			String fileStatus = iter.next();
			String fileName = iter.next();
			dictionary.put(fileName, StringUtil.componentsSeparatedByString(fileStatus, " ")); //$NON-NLS-1$
		}

		return dictionary;
	}

	private void addFilesFromDictionary(Map<String, List<String>> dictionary, boolean staged, boolean tracked)
	{
		// Iterate over all existing files
		synchronized (this.files)
		{
			for (ChangedFile file : this.files)
			{
				synchronized (dictionary)
				{
					List<String> fileStatus = dictionary.get(file.path);
					// Object found, this is still a cached / uncached thing
					if (fileStatus != null)
					{
						if (tracked)
						{
							String mode = fileStatus.get(0).substring(1);
							String sha = fileStatus.get(2);
							file.commitBlobSHA = sha;
							file.commitBlobMode = mode;

							if (staged)
								file.hasStagedChanges = true;
							else
								file.hasUnstagedChanges = true;
							if (fileStatus.get(4).equals("D")) //$NON-NLS-1$
								file.status = ChangedFile.Status.DELETED;
							else if (fileStatus.get(4).equals("U")) //$NON-NLS-1$
								file.status = ChangedFile.Status.UNMERGED;
						}
						else
						{
							// Untracked file, set status to NEW, only unstaged changes
							file.hasStagedChanges = false;
							file.hasUnstagedChanges = true;
							file.status = ChangedFile.Status.NEW;
						}

						// We handled this file, remove it from the dictionary
						dictionary.remove(file.path);
					}
					else
					{
						// Object not found in the dictionary, so let's reset its appropriate
						// change (stage or untracked) if necessary.

						// Staged dictionary, so file does not have staged changes
						if (staged)
							file.hasStagedChanges = false;
						// Tracked file does not have unstaged changes, file is not new,
						// so we can set it to No. (If it would be new, it would not
						// be in this dictionary, but in the "other dictionary").
						else if (tracked && file.status != ChangedFile.Status.NEW)
							file.hasUnstagedChanges = false;
						// Unstaged, untracked dictionary ("Other" files), and file
						// is indicated as new (which would be untracked), so let's
						// remove it
						else if (!tracked && file.status == ChangedFile.Status.NEW)
							file.hasUnstagedChanges = false;
					}
				}
			}
		}
		// Do new files only if necessary
		if (dictionary.isEmpty())
			return;

		// All entries left in the dictionary haven't been accounted for
		// above, so we need to add them to the "files" array
		synchronized (dictionary)
		{
			for (String path : dictionary.keySet())
			{
				List<String> fileStatus = dictionary.get(path);

				ChangedFile file = new ChangedFile(path);
				if (fileStatus.get(4).equals("D")) //$NON-NLS-1$
					file.status = ChangedFile.Status.DELETED;
				else if (fileStatus.get(4).equals("U")) //$NON-NLS-1$
					file.status = ChangedFile.Status.UNMERGED;
				else if (fileStatus.get(0).equals(":000000")) //$NON-NLS-1$
					file.status = ChangedFile.Status.NEW;
				else
					file.status = ChangedFile.Status.MODIFIED;

				if (tracked)
				{
					file.commitBlobMode = fileStatus.get(0).substring(1);
					file.commitBlobSHA = fileStatus.get(2);
				}

				file.hasStagedChanges = staged;
				file.hasUnstagedChanges = !staged;
				synchronized (this.files)
				{
					this.files.add(file);
				}
			}
		}
	}

	/**
	 * This method is called for each of the three processes from above. If all three are finished (self.busy == 0),
	 * then we can delete all files previously marked as deletable
	 */
	private void indexStepComplete()
	{
		// if we're still busy, do nothing :)
		if (--refreshStatus > 0)
		{
			return;
		}

		// At this point, all index operations have finished.
		// We need to find all files that don't have either
		// staged or unstaged files, and delete them

		Collection<ChangedFile> toRefresh = new ArrayList<ChangedFile>(this.files);
		List<ChangedFile> deleteFiles = new ArrayList<ChangedFile>();
		for (ChangedFile file : this.files)
		{
			if (!file.hasStagedChanges && !file.hasUnstagedChanges)
				deleteFiles.add(file);
		}

		if (!deleteFiles.isEmpty())
		{
			for (ChangedFile file : deleteFiles)
				files.remove(file);
		}
		postIndexChange(toRefresh);
	}

	private void postIndexChange(Collection<ChangedFile> changedFiles)
	{
		if (this.notify)
			this.repository.fireIndexChangeEvent(changedFiles);
		else
			this.notify = true;
	}

	public List<ChangedFile> changedFiles()
	{
		return new ArrayList<ChangedFile>(files);
	}

	public boolean stageFiles(Collection<ChangedFile> stageFiles)
	{
		List<String> args = new ArrayList<String>();
		args.add("update-index"); //$NON-NLS-1$
		args.add("--add"); //$NON-NLS-1$
		args.add("--remove"); //$NON-NLS-1$
		for (ChangedFile file : stageFiles)
		{
			args.add(file.getPath());
		}

		Map<Integer, String> result = GitExecutable.instance().runInBackground(workingDirectory,
				args.toArray(new String[args.size()]));
		int ret = result.keySet().iterator().next();

		if (ret != 0)
		{
			return false;
		}

		for (ChangedFile file : stageFiles)
		{
			file.hasUnstagedChanges = false;
			file.hasStagedChanges = true;
		}

		postIndexChange(stageFiles);
		return true;
	}

	public boolean unstageFiles(Collection<ChangedFile> unstageFiles)
	{
		StringBuilder input = new StringBuilder();
		for (ChangedFile file : unstageFiles)
		{
			input.append(file.indexInfo());
		}

		int ret = 1;
		Map<Integer, String> result = GitExecutable.instance().runInBackground(workingDirectory, input.toString(),
				null, new String[] { "update-index", "-z", "--index-info" }); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		ret = result.keySet().iterator().next();
		if (ret != 0)
		{
			return false;
		}

		for (ChangedFile file : unstageFiles)
		{
			file.hasUnstagedChanges = true;
			file.hasStagedChanges = false;
		}

		postIndexChange(unstageFiles);
		return true;
	}

	public void discardChangesForFiles(Collection<ChangedFile> discardFiles)
	{
		StringBuilder input = new StringBuilder();
		for (ChangedFile file : discardFiles)
		{
			input.append(file.getPath()).append("\0"); //$NON-NLS-1$
		}

		String[] arguments = new String[] { "checkout-index", "--index", "--quiet", "--force", "-z", "--stdin" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

		int ret = 1;
		Map<Integer, String> result = GitExecutable.instance().runInBackground(workingDirectory, input.toString(),
				null, arguments);
		ret = result.keySet().iterator().next();

		if (ret != 0)
		{
			// postOperationFailed("Discarding changes failed with return value " + ret);
			return;
		}

		for (ChangedFile file : discardFiles)
			file.hasUnstagedChanges = false;

		postIndexChange(discardFiles);
	}

	public void commit(String commitMessage)
	{
		String commitSubject = "commit: "; //$NON-NLS-1$
		int newLine = commitMessage.indexOf("\n"); //$NON-NLS-1$
		if (newLine == -1)
			commitSubject += commitMessage;
		else
			commitSubject += commitMessage.substring(0, newLine);

		repository.writetoCommitFile(commitMessage);

		postCommitUpdate("Creating tree"); //$NON-NLS-1$
		String tree = GitExecutable.instance().outputForCommand(workingDirectory, "write-tree"); //$NON-NLS-1$
		if (tree.length() != 40)
		{
			postCommitFailure("Creating tree failed"); //$NON-NLS-1$
			return;
		}

		List<String> arguments = new ArrayList<String>();
		arguments.add("commit-tree"); //$NON-NLS-1$
		arguments.add(tree);
		String parent = amend ? "HEAD^" : "HEAD"; //$NON-NLS-1$ //$NON-NLS-2$
		if (repository.parseReference(parent) != null)
		{
			arguments.add("-p"); //$NON-NLS-1$
			arguments.add(parent);
		}

		postCommitUpdate("Creating commit"); //$NON-NLS-1$
		int ret = 1;
		Map<Integer, String> result = GitExecutable.instance().runInBackground(workingDirectory, commitMessage,
				amendEnvironment, arguments.toArray(new String[arguments.size()]));
		String commit = result.values().iterator().next();
		ret = result.keySet().iterator().next();

		if (ret != 0 || commit.length() != 40)
		{
			postCommitFailure("Could not create a commit object"); //$NON-NLS-1$
			return;
		}

		postCommitUpdate("Running hooks"); //$NON-NLS-1$
		if (!repository.executeHook("pre-commit")) //$NON-NLS-1$
		{
			postCommitFailure("Pre-commit hook failed"); //$NON-NLS-1$
			return;
		}

		if (!repository.executeHook("commit-msg", repository.commitMessageFile())) //$NON-NLS-1$
		{
			postCommitFailure("Commit-msg hook failed"); //$NON-NLS-1$
			return;
		}

		postCommitUpdate("Updating HEAD"); //$NON-NLS-1$
		result = GitExecutable.instance().runInBackground(workingDirectory, "update-ref", "-m", commitSubject, "HEAD", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				commit);
		ret = result.keySet().iterator().next();

		if (ret != 0)
		{
			postCommitFailure("Could not update HEAD"); //$NON-NLS-1$
			return;
		}

		postCommitUpdate("Running post-commit hook"); //$NON-NLS-1$

		boolean success = repository.executeHook("post-commit"); //$NON-NLS-1$
		Map<String, Object> userInfo = new HashMap<String, Object>();
		userInfo.put("success", success); //$NON-NLS-1$
		String description;
		if (success)
			description = "Successfully created commit " + commit; //$NON-NLS-1$
		else
			description = "Post-commit hook failed, but successfully created commit " + commit; //$NON-NLS-1$

		userInfo.put("description", description); //$NON-NLS-1$
		userInfo.put("sha", commit); //$NON-NLS-1$

		if (!success)
			return;

		repository.hasChanged();

		amendEnvironment = null;
		if (amend)
			this.amend = false;
		else
			refresh();
	}

	private void postCommitFailure(String string)
	{
		GitPlugin.logError(string, null);
	}

	private void postCommitUpdate(String string)
	{
		GitPlugin.logInfo(string);
	}

	/**
	 * Returns the summary of all commits between two revisions.
	 * 
	 * @param sha1
	 *            SHA commit hash, or name of a ref (i.e. 'refs/heads/master')
	 * @param sha2
	 *            SHA commit hash, or name of a ref (i.e. 'refs/heads/master')
	 * @return
	 */
	String[] commitsBetween(String sha1, String sha2)
	{
		String result = GitExecutable.instance().outputForCommand(workingDirectory, "log", "--pretty=format:\"%s\"", //$NON-NLS-1$ //$NON-NLS-2$
				sha1 + ".." + sha2); //$NON-NLS-1$
		if (result == null || result.trim().length() == 0)
			return new String[0];
		return result.split("[\r\n]+"); //$NON-NLS-1$
	}

	/**
	 * @param file
	 *            the ChangedFile to generate a diff for.
	 * @param staged
	 *            Whether the file is staged or not
	 * @param contextLines
	 *            number of lines to show context for. default for underlying command is 3.
	 * @return
	 */
	public String diffForFile(ChangedFile file, boolean staged, int contextLines)
	{
		if (hasBinaryAttributes(file))
			return Messages.GitIndex_BinaryDiff_Message;

		String parameter = "-U" + contextLines; //$NON-NLS-1$
		if (staged)
		{
			String indexPath = ":0:" + file.path; //$NON-NLS-1$

			if (file.status == ChangedFile.Status.NEW)
				return GitExecutable.instance().outputForCommand(workingDirectory, "show", indexPath); //$NON-NLS-1$

			return GitExecutable.instance().outputForCommand(workingDirectory, "diff-index", parameter, "--cached", //$NON-NLS-1$ //$NON-NLS-2$
					getParentTree(), "--", file.path); //$NON-NLS-1$
		}

		// unstaged
		if (file.status == ChangedFile.Status.NEW)
		{
			try
			{
				return ProcessUtil.read(new FileInputStream(new File(workingDirectory, file.path)));
			}
			catch (FileNotFoundException e)
			{
				return null;
			}
		}

		return GitExecutable.instance().outputForCommand(workingDirectory, "diff-files", parameter, "--", file.path); //$NON-NLS-1$ //$NON-NLS-2$
	}

	public boolean hasBinaryAttributes(ChangedFile file)
	{
		String output = GitExecutable.instance().outputForCommand(workingDirectory, "check-attr", "binary", //$NON-NLS-1$ //$NON-NLS-2$
				file.getPath());
		output = output.trim();
		if (output.endsWith("binary: set")) //$NON-NLS-1$
			return true;
		if (output.endsWith("binary: unset")) //$NON-NLS-1$
			return false;
		if (output.endsWith("binary: unspecified")) //$NON-NLS-1$
		{
			// try common filename-extensions
			for (String extension : BINARY_EXTENSIONS)
			{
				if (file.getPath().endsWith(extension))
					return true;
			}
		}
		return false;
	}

	/**
	 * Used to stage/unstage/discard 'hunks' on files with changes. See http://tomayko.com/writings/the-thing-about-git
	 * 
	 * @param hunk
	 * @param stage
	 * @param reverse
	 * @return
	 */
	public boolean applyPatch(String hunk, boolean stage, boolean reverse)
	{
		List<String> array = new ArrayList<String>();
		array.add("apply"); //$NON-NLS-1$
		if (stage)
			array.add("--cached"); //$NON-NLS-1$
		if (reverse)
			array.add("--reverse"); //$NON-NLS-1$

		int ret = 1;
		Map<Integer, String> result = GitExecutable.instance().runInBackground(workingDirectory, hunk, null,
				array.toArray(new String[array.size()]));

		if (ret != 0)
		{
			GitPlugin.logError(NLS.bind("Applying patch failed with return value {0}. Error: {1}", ret, result.values() //$NON-NLS-1$
					.iterator().next()), null);
			return false;
		}

		// TODO: Try to be smarter about what to refresh
		refresh();
		return true;
	}
}
