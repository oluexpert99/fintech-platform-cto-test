package com.example.fintech.accounts.api;

import com.example.fintech.accounts.api.dto.AccountResponse;
import com.example.fintech.accounts.api.dto.BalanceResponse;
import com.example.fintech.accounts.api.dto.OpenAccountRequest;
import com.example.fintech.accounts.api.dto.PagedResponse;
import com.example.fintech.accounts.api.dto.PatchAccountRequest;
import com.example.fintech.accounts.application.AccountReadService;
import com.example.fintech.accounts.application.AccountWriteService;
import com.example.fintech.accounts.domain.exception.IdempotencyInProgressException;
import com.example.fintech.accounts.domain.exception.MissingIdempotencyKeyException;
import com.example.fintech.accounts.domain.model.AccountId;
import com.example.fintech.accounts.domain.model.UserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping(path = "/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
public class AccountsController {
    private final AccountWriteService accountWriteService;
    private final AccountReadService accountReadService;

    public AccountsController(AccountWriteService accountWriteService, AccountReadService accountReadService) {
        this.accountWriteService = accountWriteService;
        this.accountReadService = accountReadService;
    }

    @Operation(
            operationId = "openAccount",
            summary = "Open a new account",
            description = "Creates an account for the authenticated user. Idempotency-Key is required.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created"),
            @ApiResponse(responseCode = "400", description = "Missing/invalid request", content = @Content(schema = @Schema(implementation = com.example.fintech.accounts.api.dto.ProblemResponse.class))),
            @ApiResponse(responseCode = "409", description = "Idempotency conflict", content = @Content(schema = @Schema(implementation = com.example.fintech.accounts.api.dto.ProblemResponse.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountResponse> open(@AuthenticationPrincipal Jwt jwt,
                                                @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", required = true, description = "Client idempotency key")
                                                @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                                @Valid @RequestBody OpenAccountRequest request) {
        requireIdempotencyKey(idempotencyKey);
        AccountResponse response;
        try {
            response = accountWriteService.open(UserId.of(jwt.getSubject()).value(), idempotencyKey, request);
        } catch (DuplicateKeyException dup) {
            throw new IdempotencyInProgressException(2);
        }
        return ResponseEntity.created(URI.create("/v1/accounts/" + response.id())).body(response);
    }

    @Operation(
            operationId = "patchAccount",
            summary = "Patch account",
            description = "Updates editable account fields. Supports status transitions and If-Match version checks.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account updated"),
            @ApiResponse(responseCode = "403", description = "Forbidden or operator approval required", content = @Content(schema = @Schema(implementation = com.example.fintech.accounts.api.dto.ProblemResponse.class))),
            @ApiResponse(responseCode = "404", description = "Account not found", content = @Content(schema = @Schema(implementation = com.example.fintech.accounts.api.dto.ProblemResponse.class))),
            @ApiResponse(responseCode = "409", description = "Version/idempotency/state conflict", content = @Content(schema = @Schema(implementation = com.example.fintech.accounts.api.dto.ProblemResponse.class)))
    })
    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AccountResponse patch(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable String id,
                                 @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", required = true, description = "Client idempotency key")
                                 @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                 @Parameter(in = ParameterIn.HEADER, name = "If-Match", required = false, description = "Expected current version")
                                 @RequestHeader(name = "If-Match", required = false) Long ifMatchVersion,
                                 @Valid @RequestBody PatchAccountRequest request) {
        requireIdempotencyKey(idempotencyKey);
        try {
            return accountWriteService.patch(jwt.getSubject(), rolesOf(jwt), AccountId.of(id), idempotencyKey, ifMatchVersion, request);
        } catch (DuplicateKeyException dup) {
            throw new IdempotencyInProgressException(2);
        }
    }

    @Operation(operationId = "getAccountById", summary = "Get account by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account returned"),
            @ApiResponse(responseCode = "404", description = "Account not found", content = @Content(schema = @Schema(implementation = com.example.fintech.accounts.api.dto.ProblemResponse.class)))
    })
    @GetMapping("/{id}")
    public AccountResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return accountReadService.get(jwt.getSubject(), rolesOf(jwt), AccountId.of(id));
    }

    @Operation(operationId = "listAccounts", summary = "List current user's accounts")
    @GetMapping
    public PagedResponse<AccountResponse> list(@AuthenticationPrincipal Jwt jwt,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return accountReadService.list(jwt.getSubject(), page, Math.min(size, 100));
    }

    @Operation(
            operationId = "getAccountBalance",
            summary = "Get account balance",
            description = "Uses majority read concern for read-your-writes friendliness.")
    @GetMapping("/{id}/balance")
    public BalanceResponse balance(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return accountReadService.balance(jwt.getSubject(), rolesOf(jwt), AccountId.of(id));
    }

    private static Set<String> rolesOf(Jwt jwt) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
            return roles.stream().map(Object::toString).collect(Collectors.toSet());
        }
        return Set.of();
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }
    }
}
