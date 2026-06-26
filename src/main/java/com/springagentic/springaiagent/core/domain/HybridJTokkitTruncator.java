//package com.springagentic.springaiagent.core.domain;
//
//import com.knuddels.jtokkit.Encodings;
//import com.knuddels.jtokkit.api.Encoding;
//import com.knuddels.jtokkit.api.EncodingRegistry;
//import com.knuddels.jtokkit.api.EncodingType;
//import org.springframework.stereotype.Component;
//
//@Component
//public class HybridJTokkitTruncator implements ObservationTruncator {
//
//    private final Encoding encoding;
//
//    public HybridJTokkitTruncator() {
//        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
//        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
//    }
//
//    @Override
//    public String truncate(String text, int maxTokens) {
//        if (text == null) {
//            return "";
//        }
//        if (maxTokens <= 0) {
//            return "";
//        }
//
//        // 1. Estimate safe character length (average 4 chars per token)
//        int estimatedChars = maxTokens * 4;
//        int cutoffIndex = (int) (estimatedChars * 1.2);
//
//        // 2. Base check for small text
//        if (text.length() <= cutoffIndex) {
//            int tokenCount = encoding.countTokens(text);
//            if (tokenCount <= maxTokens) {
//                return text;
//            }
//        }
//
//        // 3. Prepare Warning Banner and reserve tokens
//        String warningTemplate = "\n... [SYSTEM WARNING: Payload exceeded %d tokens. MIDDLE %d CHARACTERS TRUNCATED. Please refine your tool arguments using LIMIT, OFFSET, or strict search filters.] ...\n";
//
//        // Approximate warning length for token budgeting
//        String dummyWarning = String.format(warningTemplate, maxTokens, text.length());
//        int warningTokens = encoding.countTokens(dummyWarning);
//        int remainingTokens = Math.max(0, maxTokens - warningTokens);
//
//        int headTokens = remainingTokens / 2;
//        int tailTokens = remainingTokens - headTokens;
//
//        // 4. Extract Head and Tail using Heuristics and Binary Search
//        String headPart = getHeadPart(text, headTokens, cutoffIndex);
//        String tailPart = getTailPart(text, tailTokens, cutoffIndex);
//
//        int truncatedChars = text.length() - headPart.length() - tailPart.length();
//        String finalWarning = String.format(warningTemplate, maxTokens, truncatedChars);
//
//        return headPart + finalWarning + tailPart;
//    }
//
//    private String getHeadPart(String text, int targetTokens, int cutoffIndex) {
//        if (targetTokens <= 0) {
//            return "";
//        }
//        // Heuristic slice
//        String searchRange = text.substring(0, Math.min(text.length(), cutoffIndex));
//
//        int low = 0;
//        int high = searchRange.length();
//        int bestLength = 0;
//
//        while (low <= high) {
//            int mid = (low + high) / 2;
//
//            // Adjust mid to not split surrogate pairs
//            if (mid > 0 && mid < searchRange.length() && Character.isHighSurrogate(searchRange.charAt(mid - 1))) {
//                mid--;
//            }
//
//            String candidate = searchRange.substring(0, mid);
//            int tokens = encoding.countTokens(candidate);
//
//            if (tokens <= targetTokens) {
//                bestLength = mid;
//                low = mid + 1;
//            } else {
//                high = mid - 1;
//            }
//        }
//
//        return searchRange.substring(0, bestLength);
//    }
//
//    private String getTailPart(String text, int targetTokens, int cutoffIndex) {
//        if (targetTokens <= 0) {
//            return "";
//        }
//        // Heuristic slice from the end
//        int startIndex = Math.max(0, text.length() - cutoffIndex);
//        String searchRange = text.substring(startIndex);
//
//        int low = 0;
//        int high = searchRange.length();
//        int bestLength = 0;
//
//        while (low <= high) {
//            int mid = (low + high) / 2;
//
//            int candidateStart = searchRange.length() - mid;
//            // Adjust to not split surrogate pairs
//            if (candidateStart > 0 && candidateStart < searchRange.length() && Character.isHighSurrogate(searchRange.charAt(candidateStart - 1))) {
//                mid--;
//                candidateStart = searchRange.length() - mid;
//            }
//
//            String candidate = searchRange.substring(candidateStart);
//            int tokens = encoding.countTokens(candidate);
//
//            if (tokens <= targetTokens) {
//                bestLength = mid;
//                low = mid + 1;
//            } else {
//                high = mid - 1;
//            }
//        }
//
//        return searchRange.substring(searchRange.length() - bestLength);
//    }
//}
