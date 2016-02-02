package org.protege.editor.owl.model.search.nci;

import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.event.EventType;
import org.protege.editor.owl.model.event.OWLModelManagerChangeEvent;
import org.protege.editor.owl.model.event.OWLModelManagerListener;
import org.protege.editor.owl.model.search.SearchContext;
import org.protege.editor.owl.model.search.SearchInterruptionException;
import org.protege.editor.owl.model.search.SearchManager;
import org.protege.editor.owl.model.search.SearchResult;
import org.protege.editor.owl.model.search.SearchResultHandler;
import org.protege.editor.owl.model.search.SearchSettings;
import org.protege.editor.owl.model.search.SearchSettingsListener;
import org.protege.editor.owl.model.search.SearchStringParser;
import org.protege.editor.owl.model.search.lucene.ChangeSet;
import org.protege.editor.owl.model.search.lucene.IndexDelegator;
import org.protege.editor.owl.model.search.lucene.LuceneSearcher;
import org.protege.editor.owl.model.search.lucene.QueryRunner;
import org.protege.editor.owl.model.search.lucene.ResultDocumentHandler;
import org.protege.editor.owl.model.search.lucene.SearchQueries;

import org.apache.lucene.search.IndexSearcher;
import org.semanticweb.owlapi.model.OWLException;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyChangeListener;
import org.semanticweb.owlapi.util.ProgressMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.SwingUtilities;

/**
 * Author: Josef Hardi <josef.hardi@stanford.edu><br>
 * Stanford University<br>
 * Bio-Medical Informatics Research Group<br>
 * Date: 13/11/2015
 */
public class NciSearchManager extends LuceneSearcher implements SearchManager, SearchSettingsListener {

    private static final Logger logger = LoggerFactory.getLogger(NciSearchManager.class);

    private OWLEditorKit editorKit;

    private OWLOntology activeOntology;

    private ExecutorService service = Executors.newSingleThreadExecutor();

    private AtomicLong lastSearchId = new AtomicLong(0);

    private SearchSettings settings = new SearchSettings();

    private SearchStringParser searchStringParser = new NciSearchStringParser();

    private ProgressMonitor progressMonitor;

    private NciThesaurusIndexer indexer = new NciThesaurusIndexer();

    private IndexDelegator indexDelegator;

    private IndexSearcher indexSearcher;

    private Future<?> lastIndexingTask;
    private Future<?> lastSearchingTask;

    private final OWLOntologyChangeListener ontologyChangeListener;

    private final OWLModelManagerListener modelManagerListener;

    public NciSearchManager(OWLEditorKit editorKit) {
        this.editorKit = editorKit;
        ontologyChangeListener = new OWLOntologyChangeListener() {
            public void ontologiesChanged(List<? extends OWLOntologyChange> changes) throws OWLException {
                updateIndex(changes);
            }
        };
        modelManagerListener = new OWLModelManagerListener() {
            public void handleChange(OWLModelManagerChangeEvent event) {
                handleModelManagerEvent(event);
            }
        };
        editorKit.getOWLModelManager().addOntologyChangeListener(ontologyChangeListener);
        editorKit.getModelManager().addListener(modelManagerListener);
    }

    private void handleModelManagerEvent(OWLModelManagerChangeEvent event) {
        OWLOntology ontology = editorKit.getOWLModelManager().getActiveOntology();
        if (isCacheMutatingEvent(event)) {
            boolean success = changeActiveOntology(ontology);
            if (success) {
                indexDelegator = new IndexDelegator(activeOntology);
            }
            rebuildIndex();
        }
        else if (isCacheSavingEvent(event)) {
            indexDelegator.saveVersion();
        }
    }

    private boolean changeActiveOntology(OWLOntology ontology) {
        if (activeOntology == null) {
            activeOntology = ontology;
            return true;
        }
        else {
            if (!activeOntology.equals(ontology)) {
                activeOntology = ontology;
                return true;
            }
        }
        return false;
    }

    private void rebuildIndex() {
        lastSearchId.set(0); // rebuild index
    }

    private boolean isCacheMutatingEvent(OWLModelManagerChangeEvent event) {
        return event.isType(EventType.ACTIVE_ONTOLOGY_CHANGED) || event.isType(EventType.ENTITY_RENDERER_CHANGED);
    }

    private boolean isCacheSavingEvent(OWLModelManagerChangeEvent event) {
        return event.isType(EventType.ONTOLOGY_SAVED);
    }

    @Override
    public void setProgressMonitor(ProgressMonitor pm) {
        progressMonitor = pm;
    }

    @Override
    public boolean canInterrupt() {
        return false;
    }

    @Override
    public void interrupt() throws InterruptedException {
        lastIndexingTask.cancel(true);
        lastSearchingTask.cancel(true);
    }

    @Override
    public void dispose() throws Exception {
        OWLModelManager mm = editorKit.getOWLModelManager();
        mm.removeOntologyChangeListener(ontologyChangeListener);
        mm.removeListener(modelManagerListener);
    }

    @Override
    public void handleSearchSettingsChanged() {
        // TODO Auto-generated method stub
    }

    @Override
    public IndexSearcher getIndexSearcher() {
        return indexSearcher;
    }

    @Override
    public SearchSettings getSearchSettings() {
        return settings;
    }

    @Override
    public void performSearch(String searchString, SearchResultHandler searchResultHandler) {
        if (lastSearchId.getAndIncrement() == 0) {
            lastIndexingTask = service.submit(new Runnable() {
                public void run() {
                    buildingIndex();
                }
            });
        }
        UserQueries query = prepareQuery(searchString);
        lastSearchingTask = service.submit(new SearchCallable(lastSearchId.incrementAndGet(), query, searchResultHandler));
    }

    private void buildingIndex() {
        fireIndexingStarted();
        try {
            indexer.doIndex(indexDelegator,
                    new SearchContext(editorKit),
                    progress -> fireIndexingProgressed(progress));
            reloadIndexSearcher();
        }
        catch (IOException e) {
            logger.error("... build index failed");
            e.printStackTrace();
        }
        finally {
            fireIndexingFinished();
        }
    }

    private void updateIndex(List<? extends OWLOntologyChange> changes) {
        lastIndexingTask = service.submit(new Runnable() {
            public void run() {
                updatingIndex(changes);
            }
        });
    }

    private void updatingIndex(List<? extends OWLOntologyChange> changes) {
        try {
            boolean success = indexer.doUpdate(indexDelegator,
                    new ChangeSet(changes),
                    new SearchContext(editorKit));
            if (success) {
                reloadIndexSearcher();
            }
        }
        catch (IOException e) {
            logger.error("... update index failed");
            e.printStackTrace();
        }
    }

    private void reloadIndexSearcher() {
        indexSearcher = new IndexSearcher(indexer.getIndexReader(indexDelegator));
    }

    private UserQueries prepareQuery(String searchString) {
        NciQueryBasedInputHandler handler = new NciQueryBasedInputHandler(this);
        searchStringParser.parse(searchString, handler);
        return handler.getQueryObject();
    }

    private class SearchCallable implements Runnable {

        private long searchId;
        private UserQueries userQueries;
        private SearchResultHandler searchResultHandler;
        private QueryRunner queryRunner = new QueryRunner(lastSearchId);

        private SearchCallable(long searchId, UserQueries userQueries, SearchResultHandler searchResultHandler) {
            this.searchId = searchId;
            this.userQueries = userQueries;
            this.searchResultHandler = searchResultHandler;
        }

        @Override
        public void run() {
            logger.debug("Starting search " + searchId + "\n" + userQueries);
            SearchResultManager resultManager = new SearchResultManager();
            long searchStartTime = System.currentTimeMillis();
            fireSearchStarted();
            try {
                for (Entry<SearchQueries, Boolean> queryEntry : userQueries) {
                    runQuery(queryEntry.getKey(), queryEntry.getValue(), resultManager);
                }
            }
            catch (SearchInterruptionException e) {
                return; // search terminated prematurely
            }
            catch (Throwable e) {
                logger.error("... error while searching: " + e.getMessage());
                e.printStackTrace();
                return;
            }
            fireSearchFinished();
            Set<SearchResult> results = resultManager.getSearchResults();
            long searchEndTime = System.currentTimeMillis();
            long searchTime = searchEndTime - searchStartTime;
            logger.debug("... finished search " + searchId + " in " + searchTime + " ms (" + results.size() + " results)");
            showResults(results, searchResultHandler);
        }

        private void runQuery(SearchQueries searchQueries, boolean isLinked, final SearchResultManager resultManager) throws IOException, SearchInterruptionException {
            ResultDocumentHandler documentHandler = createDocumentHandler();
            queryRunner.execute(searchId, searchQueries, documentHandler, progress -> fireSearchProgressed(progress));
            Set<SearchResult> searchResults = documentHandler.getSearchResults();
            resultManager.addSearchResults(searchResults, isLinked);
        }

        private ResultDocumentHandler createDocumentHandler() {
            return new ResultDocumentHandler(editorKit);
        }

        private void showResults(final Set<SearchResult> results, final SearchResultHandler searchResultHandler) {
            if (SwingUtilities.isEventDispatchThread()) {
                searchResultHandler.searchFinished(results);
            }
            else {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        searchResultHandler.searchFinished(results);
                    }
                });
            }
        }
    }

    private void fireIndexingStarted() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                progressMonitor.setSize(100);
                progressMonitor.setMessage("Initializing index");
                progressMonitor.setStarted();
            }
        });
    }

    private void fireIndexingProgressed(final long progress) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                progressMonitor.setProgress(progress);
                switch ((int)progress % 4) {
                    case 0: progressMonitor.setMessage("indexing"); break;
                    case 1: progressMonitor.setMessage("indexing."); break;
                    case 2: progressMonitor.setMessage("indexing.."); break;
                    case 3: progressMonitor.setMessage("indexing..."); break;
                }
            }
        });
    }

    private void fireIndexingFinished() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                progressMonitor.setFinished();
            }
        });
    }

    private void fireSearchStarted() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                progressMonitor.setSize(100);
                progressMonitor.setStarted();
            }
        });
    }

    private void fireSearchProgressed(final long progress) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                progressMonitor.setProgress(progress);
                switch ((int)progress % 4) {
                    case 0: progressMonitor.setMessage("searching"); break;
                    case 1: progressMonitor.setMessage("searching."); break;
                    case 2: progressMonitor.setMessage("searching.."); break;
                    case 3: progressMonitor.setMessage("searching..."); break;
                }
            }
        });
    }

    private void fireSearchFinished() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                progressMonitor.setFinished();
            }
        });
    }
}
