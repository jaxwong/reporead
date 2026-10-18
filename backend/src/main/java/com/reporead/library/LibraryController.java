package com.reporead.library;

import com.reporead.auth.AppUser;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** External-call ceiling: 0 GitHub requests. The phone's one read of the server's lists (ADR-08). */
@RestController
public class LibraryController {
    private final Library library;

    public LibraryController(Library library) {
        this.library = library;
    }

    /** The bytes Library measured against its cap, sent as they are; the published spec names the record they hold. */
    @GetMapping("/api/library")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Library.Snapshot.class)))
    ResponseEntity<byte[]> library(@AuthenticationPrincipal AppUser user) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(library.answer(user.id()));
    }
}
