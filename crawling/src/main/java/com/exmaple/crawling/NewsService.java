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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
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



    /** 필요 개수(want)만큼 페이지네이션 따라가며 수집 */
    @Transactional
    public List<Long> crawlCategory(String category, int want) throws Exception {
        String nextUrl = CATEGORY_URL.getOrDefault(category, CATEGORY_URL.get("economy"));

        // 전역 중복 방지
        Set<String> seen = new LinkedHashSet<>();
        List<Long> savedIds = new ArrayList<>();

        while (nextUrl != null && savedIds.size() < want) {
            Document list;
            try {
                list = Jsoup.connect(nextUrl)
                        .userAgent("Mozilla/5.0")
                        .timeout(15000)
                        .get();
            } catch (org.jsoup.HttpStatusException hse) {
                if (hse.getStatusCode() == 429 || hse.getStatusCode() == 503) {
                    // 잠깐 쉬고 재시도
                    Thread.sleep(5000);
                    continue;
                }
                throw hse;
            }

            // 이 페이지에서 기사 링크 수집
            List<String> links = list.select("a[href*=/v/]").stream()
                    .map(a -> a.attr("abs:href"))
                    .filter(seen::add) // 페이지 간 중복 제거
                    .collect(Collectors.toList());

            for (String url : links) {
                if (savedIds.size() >= want) break;
                try {
                    News news = parseArticle(url, category);
                    if (news == null) continue;
                    repo.save(news);
                    savedIds.add(news.getId());
                    politeDelay();
                } catch (Exception e) {
                    System.err.println("Failed: " + url + " => " + e.getMessage());
                }
            }

            // 다음 페이지 이동
            nextUrl = findNextPageUrl(list);
            if (nextUrl != null && nextUrl.equalsIgnoreCase(list.location())) nextUrl = null;
            politeDelay();
        }

        return savedIds;
    }

    /** 상세 기사 파싱: 제목/본문/기자/발행일 + 이미지URL들을 content에 합친다 */
    private News parseArticle(String url, String category) throws Exception {
        Document doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(10000).get();

        // 제목
        String title = meta(doc, "meta[property=og:title]");
        if (title == null) {
            Element h = doc.selectFirst("h3.tit_view");
            if (h != null) title = h.text();
        }

        // 본문 컨테이너
        Element hc = doc.selectFirst("#harmonyContainer");
        if (hc == null) hc = doc.selectFirst("#mArticle");

        if (hc != null) {
            // 화면용/광고/유틸 영역 제거
            hc.select("aside, nav, .btn_util, .util_view, .voice_area, .translate_btn, " +
                            ".tool_trans, .copyright, .foot_view, .relate_news, .kakao_ad, " +
                            ".ad_player, .realtime_view, .keyword_view")
                    .remove();
        }

        // 1) JSON-LD articleBody 우선
        String content = extractBodyFromJsonLd(doc);

        // 2) 없으면 DOM에서 p만 모으기 + UI 문자열 필터
        if (content == null || content.isBlank()) {
            Elements paras = hc != null ? hc.select("section p") : new Elements();
            if (paras.isEmpty() && hc != null) paras = hc.select("p");

            StringBuilder body = new StringBuilder();
            for (Element p : paras) {
                String t = p.text().trim();
                if (t.isBlank()) continue;
                if (t.contains("번역beta") || t.startsWith("Translated by")
                        || t.startsWith("글씨크기") || t.startsWith("인쇄하기")
                        || t.contains("무단전재") || t.contains("재배포 금지")) continue;
                body.append(t).append("\n\n");
            }
            content = body.toString().trim();
        }

        if (content == null || content.isBlank()) return null;

        // 발행일
        String published = meta(doc, "meta[property=article:published_time], meta[name=date], meta[name=pubdate]");

        // 기자명
        String author = null;
        Element rep = doc.selectFirst(".info_view .txt_info, .name_reporter");
        if (rep != null) author = rep.text();

        // 이미지 URL 수집 (원본 복원)
        String imagesBlock = collectImages(hc);
        if (!imagesBlock.isBlank()) content = content + "\n\n[IMAGES]\n" + imagesBlock;

        News n = new News();
        n.setTitle(title);
        n.setContent(content);
        n.setAuthor(author);
        n.setPostDate(published);
        n.setCategory(category);
        return n;
    }

    // ----- 헬퍼들 -----

    /** 목록 페이지에서 '다음/더보기/rel=next' 등 링크 탐색 */
    private String findNextPageUrl(Document list) {
        Element next = list.selectFirst(
                ".paging a.next, a:matchesOwn(다음|더보기|Next|›), a[rel=next], a[href*='page=']"
        );
        return next == null ? null : next.attr("abs:href");
    }

    private void politeDelay() {
        try { Thread.sleep(200 + (long)(Math.random() * 400)); } catch (InterruptedException ignored) {}
    }

    private static String meta(Document d, String sel) {
        Element e = d.selectFirst(sel);
        return e == null ? null : e.attr("content");
    }

    /** JSON-LD에서 articleBody 추출 (가장 깨끗함) */
    private String extractBodyFromJsonLd(Document doc) {
        Elements ld = doc.select("script[type=application/ld+json]");
        for (Element s : ld) {
            try {
                JsonNode node = mapper.readTree(s.data());
                if (node == null) continue;
                if (node.isArray()) {
                    for (JsonNode n : node) {
                        if (isArticleNode(n)) {
                            String body = get(n, "articleBody");
                            if (body != null && body.strip().length() > 50) return body.strip();
                        }
                    }
                } else if (isArticleNode(node)) {
                    String body = get(node, "articleBody");
                    if (body != null && body.strip().length() > 50) return body.strip();
                }
            } catch (Exception ignore) {}
        }
        return null;
    }

    /** 본문 이미지 URL 수집(+섬네일을 원본으로 복원) */
    private static String collectImages(Element container) {
        if (container == null) return "";
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (Element img : container.select("section img[src], section img[data-src], img[srcset]")) {
            String u = img.hasAttr("data-src") ? img.attr("abs:data-src") : img.attr("abs:src");
            if ((u == null || u.isBlank()) && img.hasAttr("srcset")) {
                String first = img.attr("srcset").split(",")[0].trim().split(" ")[0];
                u = img.absUrl(first);
            }
            if (u == null || u.isBlank()) continue;
            set.add(unthumbDaum(u));
        }
        return String.join("\n", set);
    }

    /** daum 썸네일(…/thumb/.../?fname=원본URL) → 원본 URL 추출 */
    private static String unthumbDaum(String u) {
        try {
            int i = u.indexOf("fname=");
            if (i >= 0) {
                String fname = u.substring(i + 6);
                int amp = fname.indexOf('&');
                if (amp >= 0) fname = fname.substring(0, amp);
                return URLDecoder.decode(fname, StandardCharsets.UTF_8);
            }
        } catch (Exception ignore) {}
        return u;
    }

    private static boolean isArticleNode(JsonNode node) {
        String t = node.has("@type") ? node.get("@type").asText("") : "";
        return "Article".equalsIgnoreCase(t) || "NewsArticle".equalsIgnoreCase(t);
    }

    private static String get(JsonNode n, String f) {
        return n.has(f) && !n.get(f).isNull() ? n.get(f).asText(null) : null;
    }

    // --- Naver: 카테고리 코드 매핑 ---
    private static final Map<String, String> NAVER_SID1 = Map.of(
            "politics", "100",
            "economy",  "101",
            "society",  "102",
            "world",    "104",
            "digital",  "105"   // IT/과학
    );

    /** 네이버: 카테고리별로 오늘자 리스트 페이지를 페이지네이션하며 want개까지 저장 */
    @Transactional
    public List<Long> crawlNaverCategory(String category, int want) throws Exception {
        String sid1 = NAVER_SID1.getOrDefault(category, "101"); // 기본 economy
        // KST 기준 오늘 날짜(YYYYMMDD)
        String date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);

        int page = 1;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        java.util.ArrayList<Long> saved = new java.util.ArrayList<>();

        while (saved.size() < want) {
            String listUrl = String.format(
                    "https://news.naver.com/main/list.naver?mode=LSD&mid=sec&sid1=%s&date=%s&page=%d",
                    sid1, date, page);

            org.jsoup.nodes.Document listDoc;
            try {
                listDoc = org.jsoup.Jsoup.connect(listUrl)
                        .userAgent("Mozilla/5.0")
                        .timeout(15000)
                        .get();
            } catch (org.jsoup.HttpStatusException hse) {
                if (hse.getStatusCode() == 429 || hse.getStatusCode() == 503) {
                    Thread.sleep(5000);
                    continue;
                }
                throw hse;
            }

            // 리스트에서 기사 상세 링크 수집 (PC read.naver 또는 mnews 둘 다 허용)
            java.util.List<String> links = listDoc.select("a[href*=read.naver], a[href*=/mnews/article/]").stream()
                    .map(a -> a.attr("abs:href"))
                    .map(this::toNaverMobileUrl) // 가급적 모바일 상세로 통일
                    .filter(seen::add)           // 전역 중복 제거
                    .collect(java.util.stream.Collectors.toList());

            // 더 이상 링크 없으면 종료(마지막 페이지)
            if (links.isEmpty()) break;

            for (String url : links) {
                if (saved.size() >= want) break;
                try {
                    News n = parseNaverArticle(url, category);
                    if (n == null) continue;
                    repo.save(n);
                    saved.add(n.getId());
                    politeDelay();
                } catch (Exception e) {
                    System.err.println("NAVER Fail " + url + " : " + e.getMessage());
                }
            }

            page++;
            politeDelay();
        }

        // 필요 개수 못 채우면 전일로 넘어가서 추가 수집(선택)
        // 주석 해제 시 연속일 수집
    /*
    if (saved.size() < want) {
        java.time.LocalDate d = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(1);
        while (saved.size() < want) {
            String date2 = d.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            page = 1;
            while (saved.size() < want) {
                String listUrl = String.format(
                        "https://news.naver.com/main/list.naver?mode=LSD&mid=sec&sid1=%s&date=%s&page=%d",
                        sid1, date2, page);
                org.jsoup.nodes.Document listDoc = org.jsoup.Jsoup.connect(listUrl)
                        .userAgent("Mozilla/5.0").timeout(15000).get();
                java.util.List<String> links = listDoc.select("a[href*=read.naver], a[href*=/mnews/article/]").stream()
                        .map(a -> a.attr("abs:href"))
                        .map(this::toNaverMobileUrl)
                        .filter(seen::add)
                        .collect(java.util.stream.Collectors.toList());
                if (links.isEmpty()) break;
                for (String url : links) {
                    if (saved.size() >= want) break;
                    try {
                        News n = parseNaverArticle(url, category);
                        if (n == null) continue;
                        repo.save(n);
                        saved.add(n.getId());
                        politeDelay();
                    } catch (Exception e) {
                        System.err.println("NAVER Fail " + url + " : " + e.getMessage());
                    }
                }
                page++;
                politeDelay();
            }
            d = d.minusDays(1);
        }
    }
    */

        return saved;
    }

    /** 네이버 상세 파싱: 모바일 뷰(n.news) 기준으로 제목/본문/이미지/기자/발행일 추출 */
    private News parseNaverArticle(String url, String category) throws Exception {
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .timeout(12000)
                .get();

        // 제목
        String title = meta(doc, "meta[property=og:title]");
        if (title == null) {
            org.jsoup.nodes.Element h2 = doc.selectFirst("h2.media_end_head_headline, h2#title_area");
            if (h2 != null) title = h2.text();
        }

        // 본문: 모바일은 보통 #dic_area
        org.jsoup.nodes.Element bodyEl = doc.selectFirst("#dic_area");
        if (bodyEl == null) {
            // 데스크탑 신형은 #newsct_article
            bodyEl = doc.selectFirst("#newsct_article");
        }

        // 화면용/공유/광고 제거
        if (bodyEl != null) {
            bodyEl.select("script, style, aside, figure[data-type=photo-raw], .promotion, .media_end_categorize, .end_photo_org")
                    .remove();
        }

        String content = "";
        if (bodyEl != null) {
            // 본문 텍스트
            String text = bodyEl.text().trim();

            // 이미지 URL 수집
            String images = collectNaverImages(bodyEl);
            content = text;
            if (!images.isBlank()) content += "\n\n[IMAGES]\n" + images;
        }
        if (title == null || content.isBlank()) return null;

        // 발행일(og:article:published_time 또는 time 태그)
        String published = meta(doc, "meta[property=article:published_time]");
        if (published == null) {
            org.jsoup.nodes.Element t = doc.selectFirst("span.media_end_head_info_datestamp_time, time");
            if (t != null) {
                String dt = t.hasAttr("datetime") ? t.attr("datetime") : t.text();
                if (dt != null && !dt.isBlank()) published = dt;
            }
        }

        // 기자명(있으면)
        String author = null;
        org.jsoup.nodes.Element jour = doc.selectFirst(".media_end_head_journalist_name, span.byline, .journalistcard_summary_name__");
        if (jour != null) author = jour.text();

        News n = new News();
        n.setTitle(title);
        n.setContent(content);
        n.setAuthor(author);
        n.setPostDate(published);
        n.setCategory(category);
        return n;
    }

    /** read.naver?oid=...&aid=... 형태를 n.news 모바일 URL로 변환 */
    private String toNaverMobileUrl(String href) {
        try {
            java.net.URI u = java.net.URI.create(href);
            String host = u.getHost() == null ? "" : u.getHost();
            String path = u.getPath() == null ? "" : u.getPath();

            // 이미 모바일(mnews)면 그대로
            if (host.contains("n.news.naver.com") && path.contains("/mnews/article/")) return href;

            // read.naver?oid=xxx&aid=yyy → /mnews/article/oid/aid
            if (host.contains("news.naver.com") && href.contains("read.naver")) {
                java.util.Map<String, String> q = splitQuery(u.getRawQuery());
                String oid = q.get("oid");
                String aid = q.get("aid");
                if (oid != null && aid != null) {
                    return "https://n.news.naver.com/mnews/article/" + oid + "/" + aid;
                }
            }
            // 이미 mnews/article 링크인 경우도 abs 처리
            if (host.contains("news.naver.com") && path.contains("/mnews/article/")) {
                return "https://n.news.naver.com" + path;
            }
        } catch (Exception ignore) {}
        return href;
    }

    private static java.util.Map<String, String> splitQuery(String query) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (query == null || query.isBlank()) return map;
        for (String p : query.split("&")) {
            int i = p.indexOf('=');
            if (i >= 0) map.put(java.net.URLDecoder.decode(p.substring(0,i), java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(p.substring(i+1), java.nio.charset.StandardCharsets.UTF_8));
            else map.put(p, "");
        }
        return map;
    }

    /** 네이버 이미지: lazy-load(data-src) 또는 srcset에서 원본에 가까운 URL 고르기 */
    private static String collectNaverImages(org.jsoup.nodes.Element container) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (org.jsoup.nodes.Element img : container.select("img")) {
            String u = img.hasAttr("data-src") ? img.attr("abs:data-src")
                    : img.hasAttr("src") ? img.attr("abs:src")
                    : null;
            // srcset이 더 고해상도면 첫 항목 사용
            if ((u == null || u.isBlank()) && img.hasAttr("srcset")) {
                String first = img.attr("srcset").split(",")[0].trim().split(" ")[0];
                u = img.absUrl(first);
            }
            if (u == null || u.isBlank()) continue;

            // 네이버 리사이즈 파라미터 제거(선택)
            u = u.replaceAll("(?i)[?&]type=[^&]+", "")
                    .replaceAll("(?i)[?&]w=\\d+", "")
                    .replaceAll("(?i)[?&]t=\\w+", "");
            set.add(u);
        }
        return String.join("\n", set);
    }
}