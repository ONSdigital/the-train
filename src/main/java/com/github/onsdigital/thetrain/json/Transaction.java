package com.github.onsdigital.thetrain.json;

import com.github.davidcarboni.cryptolite.Random;
import com.github.onsdigital.thetrain.helpers.DateConverter;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Details of a single transaction, including any files transferred and any errors encountered.
 * NB a {@link Transaction} is the unit of synchronization, so methods that manipulate the collections in this class synchronize on <code>this</code>.
 */
public class Transaction {

    public static final String STARTED = "started";
    public static final String PUBLISHING = "publishing";
    public static final String COMMIT_FAILED = "commit failed";
    public static final String COMMITTED = "committed";
    public static final String ROLLED_BACK = "rolled back";
    public static final String ROLLBACK_FAILED = "rollback failed";

    // Whilst an ID collision is technically possible it's a
    // theoretical rather than a practical consideration.
    private String id = Random.id();
    private String status = STARTED;
    private String startDate = DateConverter.toString(new Date());
    private String endDate;

    private Set<UriInfo> uriInfos = new HashSet<>();
    private Set<UriInfo> uriDeletes = new HashSet<>();
    private List<String> errors = new ArrayList<>();

    /**
     * The actual files on disk in this transaction.
     * This might differ slightly from {@link #uriInfos}
     * if there is an issue, so useful to have a direct view of these.
     */
    public Map<String, List<String>> files;

    /**
     * @return The transaction {@link #id}.
     */
    public String id() {
        return id;
    }

    /**
     * @return The transaction {@link #startDate}.
     */
    public String startDate() {
        return startDate;
    }

    /**
     * @return The transaction {@link #endDate}.
     */
    public String endDate() {
        return endDate;
    }

    public String getStatus() {
        return status;
    }

    /**
     * @return An unmodifiable set of the URIs in this transaction.
     */
    public Set<UriInfo> uris() {
        synchronized (this) {
            return Collections.unmodifiableSet(uriInfos);
        }
    }

    /**
     * @return An unmodifiable set of the URIs to delete in this transaction.
     */
    public Set<UriInfo> urisToDelete() {
        synchronized (this) {
            return Collections.unmodifiableSet(uriDeletes);
        }
    }

    /**
     * @param addedUri The URI to add to the set of URIs.
     */
    public void addUri(UriInfo addedUri) {
        synchronized (this) {
            Set<UriInfo> updated = new HashSet<>(this.uriInfos);
            updated.add(addedUri);
            this.uriInfos = updated;
            status = PUBLISHING;
        }
    }

    public void addUris(List<UriInfo> addedUris) {
        synchronized (this) {
            Set<UriInfo> updated = new HashSet<>(this.uriInfos);
            updated.addAll(addedUris);
            this.uriInfos = updated;
            status = PUBLISHING;
        }
    }

    /**
     * Add a delete command to the transaction.
     *
     * @param deleted
     */
    public void addUriDelete(UriInfo deleted) {
        synchronized (this) {
            Set<UriInfo> updated = new HashSet<>(this.uriDeletes);
            updated.add(deleted);
            this.uriDeletes = updated;
            status = PUBLISHING;
        }
    }

    public void addUriDeletes(List<UriInfo> deletes) {
        synchronized (this) {
            Set<UriInfo> updated = new HashSet<>(this.uriDeletes);
            updated.addAll(deletes);
            this.uriDeletes = updated;
            status = PUBLISHING;
        }
    }

    /**
     * @return If the status of the transaction is {@value #STARTED} or {@value #PUBLISHING}, true, otherwise false.
     */
    public boolean isOpen() {
        return StringUtils.equals(STARTED, status) || StringUtils.equals(PUBLISHING, status);
    }

    /**
     * Checks for errors in this transaction.
     *
     * @return If {@link #errors} contains anything, or if any {@link UriInfo#error error} field in {@link #uriInfos} is not blank, true.
     */
    public boolean hasErrors() {
        synchronized (this) {
            boolean result = errors.size() > 0;
            for (UriInfo uriInfo : uriInfos) {
                result |= StringUtils.isNotBlank(uriInfo.error());
            }
            return result;
        }
    }

    /**
     * @return An unmodifiable set of the URIs in this transaction.
     */
    public List<String> errors() {
        synchronized (this) {
            return Collections.unmodifiableList(errors);
        }
    }

    /**
     * @param error An error debug to be added to this transaction.
     */
    public void addError(String error) {
        synchronized (this) {
            List<String> updated = new ArrayList<>(this.errors);
            updated.add(error);
            this.errors = updated;
        }
    }

    public void commit(boolean success) {
        endDate = DateConverter.toString(new Date());
        if (success) {
            status = COMMITTED;
        } else {
            status = COMMIT_FAILED;
        }
    }

    public void rollback(boolean success) {
        endDate = DateConverter.toString(new Date());
        if (success) {
            status = ROLLED_BACK;
        } else {
            status = ROLLBACK_FAILED;
        }
    }

    @Override
    public String toString() {
        synchronized (this) {
            return id + " (" + uriInfos.size() + " URIs)";
        }
    }

    public Transaction cloneOnlyErrors() {
        Transaction clone = new Transaction();
        clone.id = this.id;
        clone.status = this.status;
        clone.startDate = this.startDate;
        clone.endDate = this.endDate;
        clone.errors = this.errors;
        for (UriInfo uriInfo : this.uriInfos) {
            if (uriInfo != null && uriInfo.error() != null)  {
                clone.uriInfos.add(uriInfo);
            }
        }
        return clone;
    }

}
