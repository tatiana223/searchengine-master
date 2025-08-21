package searchengine.services.indexing;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.util.*;

public class LemmaExtractor {

    private final LuceneMorphology luceneMorph;

    private static final List<String> EXCLUDED_PARTS = Arrays.asList("СОЮЗ", "МЕЖД", "ПРЕДЛ", "ЧАСТ");
    public LemmaExtractor() throws IOException {
        this.luceneMorph = new RussianLuceneMorphology();
    }

    public Map<String, Integer> getLemmas(String text) {
        Map<String, Integer> lemmaCounts = new HashMap<>();

        String[] words = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^а-яё\\s-]", " ")
                .split("\\s+");

        for (String word : words) {
            if (word.isBlank()) continue;
            try {
                List<String> morphInfo = luceneMorph.getMorphInfo(word);
                if (isServiceWord(morphInfo)) continue;
                List<String> normalForms = luceneMorph.getNormalForms(word);
                if (normalForms.isEmpty()) continue;

                String lemma = normalForms.get(0);

                lemmaCounts.put(lemma, lemmaCounts.getOrDefault(lemma, 0) + 1);
            } catch (Exception e) {
                System.out.println("Не удалось обработать слово: " + word);
            }
        }


        return lemmaCounts;
    }

    private boolean isServiceWord(List<String> morphInfo) {
        for (String info : morphInfo) {
            String pos = extractPos(info);
            if (EXCLUDED_PARTS.contains(pos)) {
                return true;
            }
        }

        return false;
    }

    private String extractPos(String info) {
        int spaceAfterPipe = info.indexOf(' ');
        if (spaceAfterPipe == -1) return " ";
        String tail = info.substring(spaceAfterPipe + 1).trim();
        int nextSpace = tail.indexOf(' ');
        return (nextSpace == -1 ? tail : tail.substring(0, nextSpace).toUpperCase(Locale.ROOT));
    }

    public String cleanHtml(String html) {
        return Jsoup.parse(html).text();
    }

    public static void main(String[] args) throws IOException {

        LemmaExtractor extractor = new LemmaExtractor();
        String text = "Повторное появление леопарда в Осетии позволяет предположить, что леопард постоянно обитает в некоторых районах Северного Кавказа.\n";
        Map<String, Integer> lemmas = extractor.getLemmas(text);
        lemmas.forEach((lemma, count) -> System.out.println(lemma + " - " + count));
    }


}
