package com.github.davidcarboni.thetrain.destination.json;

import com.github.davidcarboni.thetrain.destination.helpers.DateConverter;
import org.apache.commons.lang3.StringUtils;

import java.util.Date;

/**
 * Information about the transfer of a single file.
 */
public class UriInfo {

    public static final String STARTED = "started";
    public static final String UPLOADED = "uploaded";
    public static final String UPLOAD_FAILED = "upload failed";
    public static final String COMMIT_FAILED = "commit failed";
    public static final String COMMITTED = "committed";
    public static final String ROLLED_BACK = "rolled back";
    public static final String UNKNOWN = "This URI was not recorded in Transaction info";

    /**
     * This is a String rather than an enum to make deserialisation lenient.
     *
     * This should be one of the following constant values defined in this class:
     * <ul>
     *     <li>{@value #STARTED}</li>
     *     <li>{@value #UPLOADED}</li>
     *     <li>{@value #UPLOAD_FAILED}</li>
     *     <li>{@value #COMMIT_FAILED}</li>
     *     <li>{@value #COMMITTED}</li>
     *     <li>{@value #ROLLED_BACK}</li>
     *     <li>{@value #UNKNOWN}</li>
     * </ul>
     */
    String status;
    String uri;
    String start;
    String end;
    long duration;
    String sha;
    String error;

    transient Date startDate;
    transient Date endDate;

    /**
     * Constructor for serialisation.
     */
    public UriInfo() {
        // Constructor for serialisation
    }

    public String status() {
        return status;
    }

    /**
     * Normal constructor.
     *
     * @param uri       The URI to record information about.
     * @param startDate The point in time this URI started being processed.
     */
    public UriInfo(String uri, Date startDate) {
        this.uri = uri;
        this.startDate = startDate;
        start = DateConverter.toString(startDate);
        status = STARTED;
    }

    /**
     * Fallback constructor in the event that a file exists in a transaction
     * but is not present in the {@link Transaction} object.
     *
     * @param uri The URI to record information about.
     */
    public UriInfo(String uri) {
        this.uri = uri;
        status = UNKNOWN;
        error = UNKNOWN;
    }

    public String uri() {
        return uri;
    }

    /**
     * Stops this timing (only upload is timed) and updates the relevant fields.
     *
     * @return <code>this</code>.
     */
    public void stop(String sha) {
        this.sha = sha;
        endDate = new Date();
        end = DateConverter.toString(endDate);
        if (startDate != null && endDate != null) {
            duration = endDate.getTime() - startDate.getTime();
        }
        if (StringUtils.isNotBlank(sha)) {
            status = UPLOADED;
        } else {
            status = UPLOAD_FAILED;
        }
    }

    /**
     * @param error An error message to set for this Uri.
     */
    public void fail(String error) {
        status = COMMIT_FAILED;
        this.error = error;
    }

    /**
     * Sets the status of this instance to {@value #COMMITTED}.
     */
    public void commit() {
        status = COMMITTED;
    }

    /**
     * Sets the status of this instance to {@value #ROLLED_BACK}.
     */
    public void rollback() {
        status = ROLLED_BACK;
    }

    /**
     * Sets the status of this instance to {@value #COMMITTED}.
     */
    public String error() {
        return error;
    }

    /**
     * @return The file SHA fingerprint.
     */
    public String sha() {
        return sha;
    }

    // The hashCode and equals methods are used to identify this instance in the Set<Uri> in Transaction.

    @Override
    public int hashCode() {
        int result = 0;
        if (uri != null) {
            result = uri.hashCode();
        }
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        return obj != null &&
                obj.getClass().equals(this.getClass()) &&
                StringUtils.equals(uri, ((UriInfo) obj).uri);
    }

    @Override
    public String toString() {
        return uri() + " (" + status + ")";
    }
}