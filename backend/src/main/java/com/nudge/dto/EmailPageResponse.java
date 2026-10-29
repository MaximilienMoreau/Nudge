package com.nudge.dto;

import org.springframework.data.domain.Page;
import java.util.List;

/** Stable pagination JSON independent of Spring Data's internal PageImpl representation. */
public record EmailPageResponse(List<EmailDTO> content, int number, int size, int totalPages,
                                long totalElements, boolean first, boolean last) {
    public static EmailPageResponse of(Page<EmailDTO> page) {
        return new EmailPageResponse(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalPages(), page.getTotalElements(), page.isFirst(), page.isLast());
    }
}
