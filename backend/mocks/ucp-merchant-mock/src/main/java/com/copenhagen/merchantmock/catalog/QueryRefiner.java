package com.copenhagen.merchantmock.catalog;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.ko.KoreanTokenizer;
import org.apache.lucene.analysis.ko.KoreanTokenizer.DecompoundMode;
import org.apache.lucene.analysis.ko.POS;
import org.apache.lucene.analysis.ko.tokenattributes.PartOfSpeechAttribute;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

/**
 * 검색어 정제 — nori 로 어절마다 서술어·조사를 걷어내고 상품군 낱말만 남긴다(DESIGN.md 6.7절, ADR 0013 규칙 1~2).
 *
 * <p>형태소가 아니라 <b>어절</b>(공백 단위 낱말)을 단위로 삼는다. 형태소마다 명사만 남기면 사전에 없는 상품명이 아는
 * 조각으로 잘린다 — 러닝화 → 러닝+화, 에어프라이어 → 에어+프라이어, 파스타면 → 파스타+이면(3차 라운드 1 실측). 그래서 nori
 * 에게는 "이 어절에 서술어나 조사·어미가 있나" 만 묻고, 있으면 그 부분만 걷어내고, 없으면 어절 원문을 그대로 쓴다.
 *
 * <pre>
 * 1  어절에 서술어 품사(VV·VA·VX·VCN·XSV·XSA)가 있으면 어절을 버린다. 단 명사형 어미(ETN)로 끝나면 명사다(수납함)
 *    — 찾아줘·필요해·주문할래·사고·싶어 / 신선한·맛있는·부드러운·큰·진한
 * 2  어절 원문이 풀에 없을 때만 끝의 조사(J*)·어미(E*)·이다(VCP)를 걷어낸다 — 우유를 → 우유, 물놀이 → 그대로
 * 3  남은 것에 내용 품사(NNG·NNP·SL·SH·SN)가 없으면 버린다 — 거·것·하나·어디
 * 4  남은 구간의 원문을 토큰으로 쓴다 — 러닝화·에어프라이어·10만원 은 손대지 않는다
 * 5  전부 걸러지면 공백 분리 원문으로 돌아간다(안전망)
 * </pre>
 *
 * <p>조각({@link Token#pieces})은 복합어를 쪼개 읽은 MIXED 모드의 내용 품사 형태소다. 어절 토큰이 어느 상품에도 없을 때
 * 백오프가 쓴다(무선이어폰 → 무선·이어폰). 규칙 자체는 여기서 쓰지 않는다.
 *
 * <p>실제 호출자(Haiku)는 조사·서술어를 이미 걷어내고 넘기므로 시연 경로에서 이 단계의 값어치는 조사가 아니라 형용사 제거
 * (가벼운·신선한·맛있는)에 있다(장부 3.8절). 사전(mecab-ko-dic 25MB)은 nori jar 안에 있고 첫 토크나이저 생성 때 한 번 올라온다
 * (약 0.5초, 힙 +15MB). 토크나이저는 스레드에 안전하지 않아 호출마다 새로 만든다 — 사전이 공유되므로 생성은 싸다.
 *
 * <p>파이썬 벤치({@code docs/search-bench3.py})의 규칙과 같은 답을 내야 한다. 384 검색어로 대조한다.
 */
public final class QueryRefiner {

    /** 서술어 품사. 활용형 토큰(INFLECT)도 왼쪽 품사가 이 중 하나라 여기서 걸린다. */
    private static final Set<String> PREDICATE = Set.of("VV", "VA", "VX", "VCN", "XSV", "XSA");
    /** 어절 끝에서 걷어내는 것 — 조사(J*), 어미(E*), 이다(VCP). */
    private static final String[] STRIP_PREFIXES = {"J", "E", "VCP"};
    /** 내용 품사 — 일반명사·고유명사·외국어·한자·숫자. */
    private static final Set<String> CONTENT = Set.of("NNG", "NNP", "SL", "SH", "SN");
    private static final Pattern WORD = Pattern.compile("\\S+");

    /**
     * 정제된 토큰 하나.
     *
     * @param token  소문자 토큰. 어절 원문의 일부(조사를 걷어낸 구간) 또는 원문 그대로
     * @param surface 어절 원문(소문자). 수식어 판정은 토큰과 원문 둘 다로 한다 — "추천해줘" 가 "추천" 이 되어도 수식어다
     * @param pieces MIXED 모드의 내용 품사 조각(소문자, 토큰과 다른 것만). 백오프용
     */
    public record Token(String token, String surface, List<String> pieces) {
    }

    /** 형태소 하나. nori 출력 중 규칙이 보는 것만. */
    record Morpheme(String text, String type, String left, String right, int start, int end) {
        boolean inflect() {
            return "INFLECT".equals(type);
        }

        boolean compound() {
            return "COMPOUND".equals(type);
        }
    }

    public QueryRefiner() {
        // 사전 적재를 기동 때 끝낸다. 첫 검색이 0.5초를 기다리지 않도록
        analyze("예열", DecompoundMode.NONE);
    }

    /**
     * 검색어를 어절 규칙으로 정제한다. 빈 목록은 "전부 걸러졌다" 이고 호출자가 공백 분리 원문으로 돌아간다(규칙 5).
     *
     * @param query        원문 검색어(대소문자 그대로. 오프셋 계산 뒤 소문자로 눕힌다)
     * @param existsInPool 어절 원문(소문자)이 어느 상품에라도 있나. 규칙 2 의 "원문 우선" 판정
     */
    public List<Token> refine(String query, Predicate<String> existsInPool) {
        String text = query.trim();
        List<Morpheme> none = analyze(text, DecompoundMode.NONE);
        List<Morpheme> mixed = analyze(text, DecompoundMode.MIXED);
        List<Token> tokens = new ArrayList<>();
        Matcher words = WORD.matcher(text);
        while (words.find()) {
            int start = words.start();
            int end = words.end();
            String surface = words.group().toLowerCase(Locale.ROOT);
            List<Morpheme> inside = within(none, start, end);
            boolean nominalized = !inside.isEmpty() && "ETN".equals(inside.get(inside.size() - 1).right());
            boolean inPool = existsInPool.test(surface);
            if (!nominalized && inside.stream().anyMatch(m -> PREDICATE.contains(m.left()))) {
                continue; // 1 서술어가 든 어절
            }
            List<Morpheme> kept = new ArrayList<>(inside);
            if (!inPool) {
                while (!kept.isEmpty() && strippable(kept.get(kept.size() - 1).left())) {
                    kept.remove(kept.size() - 1); // 2 끝의 조사·어미·이다
                }
            }
            if (!nominalized && kept.stream().noneMatch(m -> CONTENT.contains(m.left()))) {
                continue; // 3 내용 품사 없음
            }
            if (kept.isEmpty()) {
                continue;
            }
            int spanEnd = kept.get(kept.size() - 1).end();
            String token = text.substring(kept.get(0).start(), spanEnd).toLowerCase(Locale.ROOT); // 4 원문 구간
            List<String> pieces = new ArrayList<>();
            for (Morpheme m : mixed) {
                if (start <= m.start() && m.end() <= spanEnd && !m.compound() && CONTENT.contains(m.left())) {
                    String piece = m.text().toLowerCase(Locale.ROOT);
                    if (!piece.equals(token) && !pieces.contains(piece)) {
                        pieces.add(piece);
                    }
                }
            }
            if (tokens.stream().noneMatch(t -> t.token().equals(token))) {
                tokens.add(new Token(token, surface, List.copyOf(pieces)));
            }
        }
        return tokens;
    }

    private static boolean strippable(String pos) {
        for (String prefix : STRIP_PREFIXES) {
            if (pos.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static List<Morpheme> within(List<Morpheme> morphemes, int start, int end) {
        List<Morpheme> inside = new ArrayList<>();
        for (Morpheme m : morphemes) {
            if (start <= m.start() && m.end() <= end) {
                inside.add(m);
            }
        }
        return inside;
    }

    /** nori 로 형태소·품사·오프셋을 뽑는다. 미지의 낱말은 낱자로 쪼개지 않는다(outputUnknownUnigrams false). */
    static List<Morpheme> analyze(String text, DecompoundMode mode) {
        List<Morpheme> out = new ArrayList<>();
        try (KoreanTokenizer tokenizer = new KoreanTokenizer(
                KoreanTokenizer.DEFAULT_TOKEN_ATTRIBUTE_FACTORY, null, mode, false)) {
            CharTermAttribute term = tokenizer.addAttribute(CharTermAttribute.class);
            OffsetAttribute offset = tokenizer.addAttribute(OffsetAttribute.class);
            PartOfSpeechAttribute pos = tokenizer.addAttribute(PartOfSpeechAttribute.class);
            tokenizer.setReader(new StringReader(text));
            tokenizer.reset();
            while (tokenizer.incrementToken()) {
                POS.Tag left = pos.getLeftPOS();
                POS.Tag right = pos.getRightPOS();
                out.add(new Morpheme(term.toString(), String.valueOf(pos.getPOSType()),
                        left == null ? "" : left.name(), right == null ? "" : right.name(),
                        offset.startOffset(), offset.endOffset()));
            }
            tokenizer.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
