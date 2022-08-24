package com.lostsidewalk.buffy.newsapi;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.kwabenaberko.newsapilib.NewsApiClient;
import com.kwabenaberko.newsapilib.models.Article;
import com.kwabenaberko.newsapilib.models.Source;
import com.kwabenaberko.newsapilib.models.request.EverythingRequest;
import com.kwabenaberko.newsapilib.models.request.SourcesRequest;
import com.kwabenaberko.newsapilib.models.request.TopHeadlinesRequest;
import com.kwabenaberko.newsapilib.models.response.ArticleResponse;
import com.kwabenaberko.newsapilib.models.response.SourcesResponse;
import com.lostsidewalk.buffy.Importer;
import com.lostsidewalk.buffy.StagingPost;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

import static javax.xml.bind.DatatypeConverter.printHexBinary;
import static org.apache.commons.lang3.BooleanUtils.isTrue;
import static org.apache.commons.lang3.SerializationUtils.serialize;

@Slf4j
@Component
public class NewsApiImporter implements Importer {

    @Autowired
    NewsApiImporterConfigProps configProps;

    @Autowired
    private Queue<StagingPost> successAggregator;

    @Autowired
    private Queue<Throwable> errorAggregator;

    private NewsApiClient newsApiClient;

    private NewsApiClient.ArticlesResponseCallback getArticlesResponseHandler(String tagName, String query) {
        return new NewsApiClient.ArticlesResponseCallback() {
            @Override
            public void onSuccess(ArticleResponse response) {
                try {
                    int successAggregatorSz = successAggregator.size();
                    importArticleResponse(tagName, query, response).forEach(s -> {
                        log.debug("Adding post hash={} to queue for tagName={}", s.getPostHash(), tagName);
                        successAggregator.offer(s);
                    });
                    int successAggregatorSzDiff = successAggregator.size() - successAggregatorSz;
                    log.info("Import success, tagName={}, query={}, queueSz={} (+{}), errorSz={}", tagName, query, successAggregator.size(), successAggregatorSzDiff, errorAggregator.size());
                } catch (Exception e) {
                    log.error("Import failure, tagName={}, query={} due to: {}", tagName, query, e.getMessage());
                }
            }

            @Override
            public void onFailure(Throwable throwable) {
                errorAggregator.offer(throwable);
            }
        };
    }

    @PostConstruct
    public void postConstruct() {
        //
        // setup the NewsAPI client
        //
        this.newsApiClient = new NewsApiClient(this.configProps.getKey());
        //
        // banner message
        //
        log.info("NewsAPI V2 importer constructed at {}", Instant.now());
        //
        // dump source data from /v2/top-headlines/sources on start-up (if debugSources eq true)
        //
        if (isTrue(this.configProps.getDebugSources())) {
            newsApiClient.getSources(
                    new SourcesRequest.Builder()
                            .language("en")
                            .country(this.configProps.getSourceCountry())
                            .build(),
                    new NewsApiClient.SourcesCallback() {
                        @Override
                        public void onSuccess(SourcesResponse response) {
                            for (Source source : response.getSources()) {
                                log.info("source={}", source.getName());
                            }
                        }

                        @Override
                        public void onFailure(Throwable throwable) {
                            log.error(throwable.getMessage());
                        }
                    }
            );
        }
    }

    static final String EVERY_QUERY_PROP_NAME = "everything-query";

    static final String HEADLINE_KEYWORDS_PROP_NAME = "headline-keywords";

    static class TagImportConfig {
        String everythingQuery;

        String[] headlineKeywords;
    }

    @Override
    public void doImport() {
        log.info("NewsAPI V2 importer running at {}", Instant.now());
        // map up tags to config properties;
        // for ea. tag, get everything / top headlines as needed
        buildConfigMap().forEach(this::importTag);

        try {
            Thread.sleep(this.configProps.getImportTimeoutMs());
            // TODO: add interrupt
        } catch (InterruptedException e) {
            log.warn("Import process interrupted by exception due to={}", e.getMessage());
        }

        log.info("NewsAPI V2 importer finished at {}", Instant.now());
    }

    private Map<String, TagImportConfig> buildConfigMap() {
        Map<String, TagImportConfig> configMap = new HashMap<>();
        this.configProps.getTag().forEach((propName, propValue) -> {
            String[] stringParts = propName.split("\\.");
            String tagName = stringParts[0];
            String tagPropertyName = StringUtils.removeStart(propName, tagName + ".");
            configMap.putIfAbsent(tagName, new TagImportConfig());
            TagImportConfig config = configMap.get(tagName);
            switch (tagPropertyName) {
                case EVERY_QUERY_PROP_NAME: {
                    config.everythingQuery = propValue;
                    break;
                }
                case HEADLINE_KEYWORDS_PROP_NAME: {
                    config.headlineKeywords = propValue.split(",");
                    break;
                }
            }
        });

        return configMap;
    }

    private void importTag(String tagName, TagImportConfig tagImportConfig) {
        log.debug("importing tagName={}. config={}", tagName, tagImportConfig);
        newsApiClient.getEverything(
                new EverythingRequest.Builder()
                        .q(tagImportConfig.everythingQuery)
                        .build(), getArticlesResponseHandler(tagName, tagImportConfig.everythingQuery));

        for (String k : tagImportConfig.headlineKeywords) {
            log.debug("headlineQuery={}", k);
            this.newsApiClient.getTopHeadlines(
                    new TopHeadlinesRequest.Builder()
                            .q(k)
                            .language("en")
                            .build(), getArticlesResponseHandler(tagName, k));
        }
    }

    @Override
    public String getImporterId() {
        return NEWS_API_V2_IMPORTER_ID;
    }

    private static final String NEWS_API_V2_IMPORTER_ID = "NewsApiV2";

    private static Set<StagingPost> importArticleResponse(String tagName, String query, ArticleResponse articleResponse) throws NoSuchAlgorithmException {
        Set<StagingPost> stagingPosts = new HashSet<>();
        MessageDigest md = MessageDigest.getInstance("MD5");
        for (Article a : articleResponse.getArticles()) {
            JsonElement objectSrc = GSON.toJsonTree(a);
            if (!objectSrc.isJsonObject()) {
                continue;
            }
            StagingPost p = StagingPost.from(NEWS_API_V2_IMPORTER_ID, tagName, getImporterDesc(query), a.getTitle(), a.getDescription(), a.getUrl(), a.getUrlToImage(), objectSrc.toString(), new Date(), computeHash(md, objectSrc));
            stagingPosts.add(p);
        }

        return stagingPosts;
    }

    private static String getImporterDesc(String query) {
        return String.format("[query=%s]", query);
    }

    private static final Gson GSON = new Gson();

    private static String computeHash(MessageDigest md, JsonElement objectSrc) {
        return printHexBinary(md.digest(serialize(objectSrc.toString())));
    }
}
