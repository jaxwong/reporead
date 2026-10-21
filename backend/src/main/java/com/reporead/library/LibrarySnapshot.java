package com.reporead.library;

import com.reporead.annotation.Bookmarks;
import com.reporead.annotation.Reviews;
import com.reporead.reading.ReadingStates;
import java.util.List;

/** The backend's complete response used to refresh the phone's local projection. */
public record LibrarySnapshot(
        List<LibraryRepository> repositories,
        List<ReadingStates.State> readingStates,
        List<Bookmarks.Bookmark> bookmarks,
        Reviews.Notebook notebook) {}
