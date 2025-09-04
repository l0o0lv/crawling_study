package com.exmaple.crawling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NewsService {
    private final NewsRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Map<String, String> CATEGORY_URL = Map.of(
            "politics", "https://news.daum.net/politics",
            "economy",  "https://news.daum.net/economy",
            "society",  "https://news.daum.net/society",
            "world",    "https://news.daum.net/world",
            "digital",  "https://news.daum.net/digital"
    );

    /**
     * 카테고리 목록에서 기사 상세를 파싱해 DB 저장
     */
    @Transactional
    public List<Long> crawlCategory(String category, int limit) throws Exception {
        String listUrl = CATEGORY_URL.getOrDefault(category, CATEGORY_URL.get("economy"));
        Document list = Jsoup.connect(listUrl)
                .userAgent("Mozilla/5.0")
                .timeout(10000)
                .get();

        // 다음 카테고리 목록에서 기사 링크 수집 (/v/ 포함 링크)
        List<String> links = list.select("a[href*=/v/]").stream()
                .map(a -> a.attr("abs:href"))
                .distinct()
                .limit(limit)
                .collect(Collectors.toList());

        List<Long> savedIds = new ArrayList<>();
        for (String url : links) {
            try {
                News news = parseArticle(url, category);
                if (news == null) continue;
                repo.save(news);
                savedIds.add(news.getId());
                Thread.sleep(300); // 과도한 요청 방지
            } catch (Exception e) {
                System.err.println("Failed: " + url + " => " + e.getMessage());
            }
        }
        return savedIds;
    }

    /** 상세 기사 파싱 (제목/본문/기자/발행일 + 본문 이미지 URL을 content에 합쳐 저장) */
    private News parseArticle(String url, String category) throws Exception {
        Document doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(10000).get();

        // 1) 제목
        String title = meta(doc, "meta[property=og:title]");
        if (title == null) {
            Element h = doc.selectFirst("h3.tit_view"); // 다음 뉴스에 흔함
            if (h != null) title = h.text();
        }

        // 2) 본문 컨테이너를 좁게 지정 (#harmonyContainer)
        Element hc = doc.selectFirst("#harmonyContainer");
        if (hc == null) hc = doc.selectFirst("#mArticle"); // 혹시 없을 때만 대체

        // 화면용 요소 제거(번역/음성/유틸/저작권 등)
        if (hc != null) {
            hc.select("aside, nav, .btn_util, .util_view, .voice_area, .translate_btn, " +
                            ".tool_trans, .copyright, .foot_view, .relate_news, .kakao_ad, .ad_player")
                    .remove();
        }

        // 3) 본문: section p 만 취합(불필요한 p 필터링)
        Elements paras = hc != null ? hc.select("section p") : new Elements();
        if (paras.isEmpty() && hc != null) paras = hc.select("p");

        StringBuilder body = new StringBuilder();
        for (Element p : paras) {
            String t = p.text().trim();
            if (t.isBlank()) continue;
            // 화면 UI/푸터/번역 라벨 등 휴리스틱 필터
            if (t.contains("번역beta") || t.startsWith("Translated by")
                    || t.startsWith("글씨크기") || t.startsWith("인쇄하기")
                    || t.contains("무단전재") || t.contains("재배포 금지")) continue;
            body.append(t).append("\n\n");
        }
        String content = body.toString().trim();
        if (content.isEmpty()) return null; // 최소 요건

        // 4) 발행일(문자열 그대로 저장)
        String published = meta(doc, "meta[property=article:published_time], meta[name=date], meta[name=pubdate]");

        // 5) 기자명(있을 때만)
        String author = null;
        Element rep = doc.selectFirst(".info_view .txt_info, .name_reporter");
        if (rep != null) author = rep.text();

        // 6) 본문 이미지 URL들(섬네일이면 원본 복원)
        String imagesBlock = collectImages(hc);
        if (!imagesBlock.isBlank()) {
            content = content + "\n\n[IMAGES]\n" + imagesBlock;
        }

        News n = new News();
        n.setTitle(title);
        n.setContent(content);
        n.setAuthor(author);
        n.setPostDate(published);
        n.setCategory(category);
        return n;
    }

    private static String meta(Document d, String sel) {
        Element e = d.selectFirst(sel);
        return e == null ? null : e.attr("content");
    }

    private static String collectImages(Element container) {
        if (container == null) return "";
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (Element img : container.select("section img[src], section img[data-src], img[srcset]")) {
            String u = img.hasAttr("data-src") ? img.attr("abs:data-src") : img.attr("abs:src");
            if ((u == null || u.isBlank()) && img.hasAttr("srcset")) {
                String first = img.attr("srcset").split(",")[0].trim().split(" ")[0];
                u = img.absUrl(first);
            }
            if (u == null || u.isBlank()) continue;
            set.add(unthumbDaum(u)); // 썸네일이면 원본 URL로 변환
        }
        return String.join("\n", set);
    }

    // daum 썸네일(…/thumb/.../?fname=원본URL) → 원본 URL 추출
    private static String unthumbDaum(String u) {
        try {
            int i = u.indexOf("fname=");
            if (i >= 0) {
                String fname = u.substring(i + 6);
                int amp = fname.indexOf('&');
                if (amp >= 0) fname = fname.substring(0, amp);
                return java.net.URLDecoder.decode(fname, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignore) {}
        return u;
    }

    private static boolean isArticleNode(JsonNode node) {
        String t = node.has("@type") ? node.get("@type").asText("") : "";
        return "Article".equalsIgnoreCase(t) || "NewsArticle".equalsIgnoreCase(t);
    }

    // placeholder: 일부 사이트가 JSON-LD를 스크립트로 감싸는 형식을 대비 (현재는 직접 필드만 사용)
    private static void fillFromLd(JsonNode node) {}

    private static String get(JsonNode n, String f) { return n.has(f) && !n.get(f).isNull() ? n.get(f).asText(null) : null; }

    private static Element first(Document d, String... sels) {
        for (String s : sels) {
            Element e = d.selectFirst(s);
            if (e != null) return e;
        }
        return null;
    }
}
