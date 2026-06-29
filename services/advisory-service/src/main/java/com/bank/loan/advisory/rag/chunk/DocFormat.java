package com.bank.loan.advisory.rag.chunk;

/**
 * 사이드카 파싱 대상 문서 포맷.
 *
 * AUTO 는 사이드카가 파일 시그니처/확장자로 판별하도록 위임한다.
 */
public enum DocFormat {
    PDF,
    DOCX,
    HWP,
    HWPX,
    TXT,
    AUTO;

    /** 파일명 확장자 → 포맷. 미상이면 AUTO(사이드카가 시그니처로 재판별). */
    public static DocFormat fromFilename(String filename) {
        if (filename == null || !filename.contains(".")) return AUTO;
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "pdf" -> PDF;
            case "docx", "doc" -> DOCX;
            case "hwp" -> HWP;
            case "hwpx" -> HWPX;
            case "txt" -> TXT;
            default -> AUTO;
        };
    }
}
