package org.bookdash.android.data.books;

import android.support.annotation.NonNull;
import android.util.Log;

import com.google.gson.Gson;
import com.parse.FindCallback;
import com.parse.GetCallback;
import com.parse.GetDataCallback;
import com.parse.ParseException;
import com.parse.ParseQuery;
import com.parse.ProgressCallback;

import org.bookdash.android.BookDashApplication;
import org.bookdash.android.domain.pojo.Book;
import org.bookdash.android.domain.pojo.BookContributor;
import org.bookdash.android.domain.pojo.BookDetail;
import org.bookdash.android.domain.pojo.Language;
import org.bookdash.android.domain.pojo.gson.BookPages;
import org.bookdash.android.data.utils.FileManager;
import org.bookdash.android.data.utils.ZipManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import bolts.Task;

/**
 * @author rebeccafranks
 * @since 15/11/03.
 */
public class BookDetailApiImpl implements BookDetailApi {

    @Override
    public void getBooksForLanguages(String language, final BookServiceCallback<List<BookDetail>> bookServiceCallback) {
        ParseQuery<Language> queryLanguagesNew = ParseQuery.getQuery(Language.class);
        queryLanguagesNew.whereEqualTo("language_name", language);

        ParseQuery<BookDetail> queryBookDetail = ParseQuery.getQuery(BookDetail.class);
        queryBookDetail.setCachePolicy(ParseQuery.CachePolicy.NETWORK_ELSE_CACHE);
        queryBookDetail.include("book_language");
        queryBookDetail.include("book_id");
        queryBookDetail.whereEqualTo("book_enabled", true);
        queryBookDetail.addDescendingOrder("createdAt");
        queryBookDetail.whereMatchesQuery("book_language", queryLanguagesNew);
        queryBookDetail.findInBackground(new FindCallback<BookDetail>() {
            @Override
            public void done(List<BookDetail> list, ParseException e) {
                if (e != null) {
                    bookServiceCallback.onError(e);
                    return;
                }
                bookServiceCallback.onLoaded(list);
            }
        });
    }

    private static final String TAG = "BookDetailApiImpl";
    private final Executor DISK_EXECUTOR = Executors.newCachedThreadPool();

    @Override
    public void getBookDetail(String bookDetailId, final BookServiceCallback<BookDetail> bookServiceCallback) {
        ParseQuery<BookDetail> queryBookDetail = ParseQuery.getQuery(BookDetail.class);
        queryBookDetail.whereEqualTo("objectId", bookDetailId);
        queryBookDetail.setCachePolicy(ParseQuery.CachePolicy.CACHE_ELSE_NETWORK);
        queryBookDetail.include("book_language");
        queryBookDetail.include("book_id");
        queryBookDetail.whereEqualTo("book_enabled", true);
        queryBookDetail.getFirstInBackground(new GetCallback<BookDetail>() {
            @Override
            public void done(BookDetail bookDetail, ParseException e) {
                if (e != null) {
                    bookServiceCallback.onError(e);
                    return;
                }
                bookServiceCallback.onLoaded(bookDetail);
            }
        });
    }

    @Override
    public void getContributorsForBook(Book bookId, final BookServiceCallback<List<BookContributor>> contributorsCallback) {

        ParseQuery<BookContributor> query = ParseQuery.getQuery(BookContributor.class);
        query.setCachePolicy(ParseQuery.CachePolicy.CACHE_ELSE_NETWORK);
        query.whereEqualTo("book", bookId);
        query.include("contributor");
        query.findInBackground(new FindCallback<BookContributor>() {
            @Override
            public void done(List<BookContributor> list, ParseException e) {
                if (e != null) {
                    contributorsCallback.onError(e);
                    return;
                }
                contributorsCallback.onLoaded(list);
            }
        });


    }


    @Override
    public void getLanguages(final BookServiceCallback<List<Language>> languagesCallback) {
        ParseQuery<Language> queryLanguages = ParseQuery.getQuery(Language.class);
        queryLanguages.setCachePolicy(ParseQuery.CachePolicy.NETWORK_ELSE_CACHE);

        queryLanguages.findInBackground(new FindCallback<Language>() {
            @Override
            public void done(List<Language> list, ParseException e) {
                if (e != null) {
                    languagesCallback.onError(e);
                    return;
                }
                languagesCallback.onLoaded(list);
            }
        });
    }


    @Override
    public void downloadBook(final BookDetail bookInfo, @NonNull final BookServiceCallback<BookPages> downloadBookCallback, @NonNull final BookServiceProgressCallback progressCallback) {

        bookInfo.getBookFile().getDataInBackground(new GetDataCallback() {
            @Override
            public void done(final byte[] bytes, ParseException e) {
                if (e != null) {
                    downloadBookCallback.onError(e);
                    return;
                }
                getBookPages(bookInfo, bytes, new BookServiceCallback<BookPages>() {
                    @Override
                    public void onLoaded(BookPages result) {
                        downloadBookCallback.onLoaded(result);
                    }

                    @Override
                    public void onError(Exception error) {
                        downloadBookCallback.onError(error);
                    }
                });


            }
        }, new ProgressCallback() {
            @Override
            public void done(Integer progressInt) {
                progressCallback.onProgressChanged(progressInt);
            }
        });
    }

    private void getBookPages(final BookDetail bookInfo, final byte[] bytes, final BookServiceCallback<BookPages> bookServiceCallback) {
        Task.call(new Callable<BookPages>() {
            @Override
            public BookPages call() throws Exception {
                BookPages bookPages = saveBook(bytes, bookInfo);
                if (bookPages == null) {
                    bookServiceCallback.onError(new Exception("Failed to save book"));
                    return null;
                }
                bookServiceCallback.onLoaded(bookPages);
                return bookPages;
            }
        }, DISK_EXECUTOR);
    }


    private BookPages saveBook(byte[] bytes, BookDetail bookDetail) {
        String targetLocation = BookDashApplication.FILES_DIR + File.separator + bookDetail.getObjectId();
        String fileLocation = BookDashApplication.FILES_DIR + File.separator + bookDetail.getBookFile().getName();

        File f = new File("", targetLocation);
        if (!f.exists()) {
            FileManager.saveFile(BookDashApplication.FILES_DIR, bytes, File.separator + bookDetail.getBookFile().getName());
            ZipManager zipManager = new ZipManager();
            zipManager.unzip(fileLocation, targetLocation);
            FileManager.deleteFile(BookDashApplication.FILES_DIR, File.separator + bookDetail.getBookFile().getName());
        }

        return getBookPages(bookDetail.getFolderLocation(BookDashApplication.FILES_DIR) + File.separator + "bookdetails.json");
    }


    private BookPages getBookPages(String fileName) {
        Gson gson = new Gson();
        BufferedReader br = null;
        BookPages bookPages = null;
        try {
            br = new BufferedReader(new FileReader(fileName));
            bookPages = gson.fromJson(br, BookPages.class);
        } catch (FileNotFoundException e) {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (IOException e1) {
                Log.e(TAG, "EX: ", e);
            }

            Log.e(TAG, "Ex:" + e.getMessage(), e);
        } catch (Exception e) {
            Log.e(TAG, "error parsing book: " + fileName, e);
        }
        return bookPages;
    }
}
