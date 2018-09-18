package com.devfactory.codefix.service;

import com.devfactory.codefix.client.codeserver.CreatedTicketId;
import com.devfactory.codefix.client.codeserver.CreatedTicketUrl;
import com.devfactory.codefix.client.codeserver.TicketCreationClient;
import com.devfactory.codefix.client.codeserver.TicketData;
import com.devfactory.codefix.common.exception.IssueNotFoundException;
import com.devfactory.codefix.common.exception.ValidationException;
import com.devfactory.codefix.repository.IssueRepository;
import com.devfactory.codefix.repository.RepositoryRepository;
import com.devfactory.codefix.repository.model.Issue;
import com.devfactory.codefix.service.dto.ExportIssuesRequest;
import com.devfactory.codefix.service.dto.IssueToExport;
import com.devfactory.codefix.service.dto.TicketBase;
import com.devfactory.codefix.service.dto.TicketRef;
import com.devfactory.codefix.service.dto.Validation;
import com.google.common.base.Strings;
import io.jsonwebtoken.lang.Collections;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Service
@AllArgsConstructor
public class TicketsService {

    private static final String FIELD_IS_REQUIRED = "field is required";
    private static final String LF = "\n";

    private final TicketCreationClient ticketingCreationClient;
    private final IssueRepository issueRepository;
    private final RepositoryRepository repositoryRepository;

    public List<TicketRef> exportTickets(ExportIssuesRequest exportRequest, String codeServerAuthorization) {
        validateExportRequest(exportRequest);
        Map<String, List<IssueToExport>> issuesGroupedByHash = groupIssuesByHash(exportRequest);
        Date now = new Date();
        List<TicketRef> ticketRefs = new ArrayList<>();
        for (Entry<String, List<IssueToExport>> entry : issuesGroupedByHash.entrySet()) {
            List<IssueToExport> issues = entry.getValue();
            TicketRef ticketRef = createTicketForIssues(exportRequest, codeServerAuthorization, now, issues);
            ticketRefs.add(ticketRef);
        }
        return ticketRefs;
    }

    private TicketRef createTicketForIssues(ExportIssuesRequest exportRequest, String codeServerAuthorization, Date now,
            List<IssueToExport> issues) {
        IssueToExport first = issues.get(0);
        Issue issue = getIssue(first);

        StringBuilder ticketDetails = new StringBuilder(500);
        ticketDetails.append("Insight Type: ").append(issue.getInsightType().getName()).append(LF)
                .append("External reference ID: ").append(first.getExternalInsightId()).append(LF) 
                .append("Priority: ").append(first.getPrioritization()).append(LF)
                .append("Locations: ").append(LF);
        for (IssueToExport eachIssue: issues) {
            ticketDetails.append("\tfile: "). append(eachIssue.getFile()).append(LF)
                .append("\tstart line: ").append(eachIssue.getStartLine()).append(LF)
                .append("\tend line: ").append(eachIssue.getEndLine()).append(LF)
                .append(eachIssue.getGitHubBlock()).append(LF)
                .append(LF);
        }
                        
        ticketDetails.append("Repository url: ").append(extractRepository(first.getRepository())).append(LF)
                .append("Affected branch: ").append(extractBranch(first.getRepository())).append(LF)
                .append("Affected revision: ").append(first.getRevision()).append("\n\n");

        String ticketName = String.format("CodeFix - %s  - %s line %s", issue.getInsightType().getName(), 
                first.getFile(), first.getStartLine());        
        TicketBase ticketBase = TicketBase.builder()
                .ticketingSystemId(exportRequest.getTicketingSystemId().toString())
                .name(ticketName)
                .description(ticketDetails.toString())
                .build();

        return createTicketAndUpdateIssue(codeServerAuthorization, ticketBase, issue, now);
    }

    private Issue getIssue(IssueToExport first) {
        Optional<Issue> optionalIssue = issueRepository
                .findByRepositoryDfScmUrlAndInsightTypeCodeAndIssueHash(first.getRepository(),
                        first.getInsightType(), first.getIssueHash());
        if (!optionalIssue.isPresent()) {
            throw new IssueNotFoundException(first.getRepository(), first.getIssueHash());
        }
        return optionalIssue.get();
    }

    private Map<String, List<IssueToExport>> groupIssuesByHash(ExportIssuesRequest exportRequest) {
        Map<String, List<IssueToExport>> issuesGroupedByHash = new HashMap<>();
        for (IssueToExport issue : exportRequest.getIssues()) {
            List<IssueToExport> issuesList = issuesGroupedByHash
                    .computeIfAbsent(issue.getIssueHash(), (key) -> new ArrayList<>());
            issuesList.add(issue);
        }
        return issuesGroupedByHash;
    }

    private TicketRef createTicketAndUpdateIssue(String codeServerAuthorization, TicketBase ticketBase, Issue issue,
            Date now) {

        TicketRef ticketRef = createTicket(ticketBase, codeServerAuthorization);
        issue.setCsTicketExportDate(now);
        issue.setCsTicketId(ticketRef.getId());
        issue.setCsTicketUrl(ticketRef.getUrl());
        issueRepository.save(issue);
        return ticketRef;
    }

    private String extractBranch(String url) {
        MultiValueMap<String, String> params = UriComponentsBuilder.fromUriString(url).build()
                .getQueryParams();
        return params.getFirst("branch");
    }

    private String extractRepository(String url) {
        int pos = url.indexOf('?');
        if (pos == -1) {
            return url;
        }
        return url.substring(0, pos);
    }

    private void validateExportRequest(ExportIssuesRequest exportRequest) {
        List<Validation> validationItems = new ArrayList<>();

        ExportIssuesRequest exportRequestToValidate = exportRequest == null ? new ExportIssuesRequest() : exportRequest;

        if (exportRequestToValidate.getTicketingSystemId() == null) {
            validationItems
                    .add(Validation.builder().field("ticketingSystemId").message(FIELD_IS_REQUIRED).build());
        }

        if (exportRequestToValidate.getIssues() == null) {
            exportRequestToValidate.setIssues(new ArrayList<>());
        }

        if (Collections.isEmpty(exportRequestToValidate.getIssues())) {
            exportRequestToValidate.getIssues().add(IssueToExport.builder().build());
        }

        validateFields(exportRequestToValidate, validationItems);

        if (!validationItems.isEmpty()) {
            throw new ValidationException(0, HttpStatus.BAD_REQUEST, "Required parameters must be provided", "",
                    validationItems);
        }
        
        List<Validation> validationItems = new ArrayList<>();

        ExportIssuesRequest exportRequestToValidate = exportRequest == null ? new ExportIssuesRequest() : exportRequest;

        if (exportRequestToValidate.getTicketingSystemId() == null) {
            validationItems
                    .add(Validation.builder().field("ticketingSystemId").message(FIELD_IS_REQUIRED).build());
        }

        if (exportRequestToValidate.getIssues() == null) {
            exportRequestToValidate.setIssues(new ArrayList<>());
        }

        if (Collections.isEmpty(exportRequestToValidate.getIssues())) {
            exportRequestToValidate.getIssues().add(IssueToExport.builder().build());
        }

        validateFields(exportRequestToValidate, validationItems);

        if (!validationItems.isEmpty()) {
            throw new ValidationException(0, HttpStatus.BAD_REQUEST, "Required parameters must be provided", "",
                    validationItems);
        }
        
        List<Validation> validationItems = new ArrayList<>();

        ExportIssuesRequest exportRequestToValidate = exportRequest == null ? new ExportIssuesRequest() : exportRequest;

        if (exportRequestToValidate.getTicketingSystemId() == null) {
            validationItems
                    .add(Validation.builder().field("ticketingSystemId").message(FIELD_IS_REQUIRED).build());
        }

        if (exportRequestToValidate.getIssues() == null) {
            exportRequestToValidate.setIssues(new ArrayList<>());
        }

        if (Collections.isEmpty(exportRequestToValidate.getIssues())) {
            exportRequestToValidate.getIssues().add(IssueToExport.builder().build());
        }

        validateFields(exportRequestToValidate, validationItems);

        if (!validationItems.isEmpty()) {
            throw new ValidationException(0, HttpStatus.BAD_REQUEST, "Required parameters must be provided", "",
                    validationItems);
        }
    }

    private void validateFields(ExportIssuesRequest exportRequest, List<Validation> validationItems) {
        for (IssueToExport issue : exportRequest.getIssues()) {
            addIfNullOrEmpty(validationItems, issue.getInsightType(), "insightType");
            addIfNullOrEmpty(validationItems, issue.getRepository(), "repository");
            addIfNullOrEmpty(validationItems, issue.getRevision(), "revision");
            addIfNullOrEmpty(validationItems, issue.getRevisionDate(), "revisionDate");
            addIfNullOrEmpty(validationItems, issue.getExternalInsightId(), "externalInsightId");
            addIfNullOrEmpty(validationItems, issue.getPrioritization(), "prioritization");            
            addIfNullOrEmpty(validationItems, issue.getIssueHash(), "issueHash");
            addIfNullOrEmpty(validationItems, issue.getRepositoryUrl(), "repositoryUrl");
            addIfNullOrEmpty(validationItems, issue.getFile(), "file");
            addIfNullOrEmpty(validationItems, issue.getGitHubBlock(), "gitHubBlock");
            addIfNull(validationItems, issue.getStartLine(), "startLine");
            addIfNull(validationItems, issue.getEndLine(), "endLine");
        }
    }

    private void addIfNullOrEmpty(List<Validation> validationItems, String value, String field) {
        if (Strings.isNullOrEmpty(value)) {
            validationItems
                    .add(Validation.builder().field(field).message(FIELD_IS_REQUIRED).build());
        }
    }

    private void addIfNull(List<Validation> validationItems, Integer value, String field) {
        if (value == null) {
            validationItems
                    .add(Validation.builder().field(field).message(FIELD_IS_REQUIRED).build());
        }
    }

    private TicketRef createTicket(TicketBase ticketBase, String codeServerAuthorization) {
        TicketData ticketData = TicketData.builder()
                .ticketingSystemId(Long.valueOf(ticketBase.getTicketingSystemId()))
                .title(ticketBase.getName())
                .description(ticketBase.getDescription())
                .build();
                TicketData ticketData2 = TicketData.builder()
                .ticketingSystemId(Long.valueOf(ticketBase.getTicketingSystemId()))
                .title(ticketBase.getName())
                .description(ticketBase.getDescription())
                .build();

        CreatedTicketId createdTicketId = ticketingCreationClient.postUserTickets(ticketData, codeServerAuthorization);

        CreatedTicketUrl createdTicketUrl = ticketingCreationClient
                .getTicketUrlById(createdTicketId.getTicketId(), codeServerAuthorization);

        return TicketRef.builder()
                .id(createdTicketId.getTicketId().toString())
                .url(createdTicketUrl.getUrl())
                .build();
    }

}

