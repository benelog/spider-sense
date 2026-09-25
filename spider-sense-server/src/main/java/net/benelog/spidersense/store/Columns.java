package net.benelog.spidersense.store;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

import org.jspecify.annotations.Nullable;

/**
 * The widths of the text columns {@link Schema} creates, and the helpers that
 * bind a value to them.
 *
 * <p>Every text value is cut to its column before it is bound (storage.adoc#writer):
 * a value one character too long would fail the whole flush or import it came in.
 * The widths are named here, beside the DDL that declares them, so the writer, the
 * importer, the marks and the acknowledgements cut to the same number.
 */
final class Columns {

    /** {@code service.name}, {@code db_table.service} and every other service name. */
    static final int SERVICE = 255;
    /** {@code service.language}. */
    static final int LANGUAGE = 64;
    /** Every JSON column: {@code attributes}, {@code events}, {@code resource}, {@code indexes}. */
    static final int JSON_TEXT = 65535;

    /** {@code span.name}. */
    static final int SPAN_NAME = 1024;
    /** {@code span.status_message}. */
    static final int STATUS_MESSAGE = 4096;
    /** {@code span.endpoint}. */
    static final int ENDPOINT = 1024;
    /** {@code span.http_method}. */
    static final int HTTP_METHOD = 16;
    /** {@code span.http_route}. */
    static final int HTTP_ROUTE = 1024;
    /** {@code span.db_system}. */
    static final int DB_SYSTEM = 64;
    /** {@code span.db_namespace}. */
    static final int DB_NAMESPACE = 255;
    /** {@code span.db_operation}. */
    static final int DB_OPERATION = 64;
    /** {@code span.db_table}. */
    static final int DB_TABLE = 255;
    /** {@code span.error_type}. */
    static final int ERROR_TYPE = 512;
    /** {@code span.error_message}. */
    static final int ERROR_MESSAGE = 4096;
    /** {@code span.scope}. */
    static final int SCOPE = 255;

    /** {@code trace.root_name}. */
    static final int TRACE_ROOT_NAME = 1024;
    /** {@code trace.services}, a JSON array that leaves out the names past it. */
    static final int TRACE_SERVICES = 4096;

    /** {@code log.severity}. */
    static final int LOG_SEVERITY = 8;
    /** {@code log.body}. */
    static final int LOG_BODY = 65535;
    /** {@code log.logger}. */
    static final int LOGGER = 512;

    /** {@code tingle.title}. */
    static final int TINGLE_TITLE = 1024;
    /** {@code tingle.detail}. */
    static final int TINGLE_DETAIL = 4096;

    /** {@code db_table.schema_name} and {@code db_table.table_name}. */
    static final int CATALOG_NAME = 255;
    /** {@code db_table.product}. */
    static final int DB_PRODUCT = 64;

    /** {@code metric.unit}. */
    static final int METRIC_UNIT = 64;
    /** {@code metric.description}. */
    static final int METRIC_DESCRIPTION = 1024;
    /** {@code metric_series.attributes}. */
    static final int SERIES_ATTRIBUTES = 4096;
    /** {@code metric_point.buckets}: a point whose buckets do not fit is stored without them. */
    static final int BUCKETS = 8192;

    /** {@code mark.note}. */
    static final int MARK_NOTE = 1024;
    /** {@code ack.note}. */
    static final int ACK_NOTE = 1024;

    private Columns() {
    }

    /** {@code value} cut to {@code max} characters, or null for null. */
    static @Nullable String cut(@Nullable String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** A nullable integer column: null binds as SQL NULL rather than as zero. */
    static void setLong(PreparedStatement statement, int index, @Nullable Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    /** A double, or NULL for NaN, which a histogram's absent min or max is. */
    static void setDouble(PreparedStatement statement, int index, double value) throws SQLException {
        if (Double.isNaN(value)) {
            statement.setNull(index, Types.DOUBLE);
        } else {
            statement.setDouble(index, value);
        }
    }
}
