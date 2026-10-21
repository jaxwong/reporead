package com.reporead.library;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.reporead.document.Documents;
import com.reporead.repository.RepositoryConnections;
import java.util.List;

/** One connected repository in a library snapshot, with every document including deleted ones. */
public record LibraryRepository(
        @JsonUnwrapped RepositoryConnections.Connection connection, List<Documents.Summary> documents) {}
