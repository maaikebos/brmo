package nl.b3p.brmo.service.stripes;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import net.sourceforge.stripes.action.*;
import net.sourceforge.stripes.controller.LifecycleStage;
import net.sourceforge.stripes.validation.Validate;
import nl.b3p.brmo.loader.BrmoFramework;
import nl.b3p.brmo.loader.ProgressUpdateListener;
import nl.b3p.brmo.loader.entity.Bericht;
import nl.b3p.brmo.loader.advancedfunctions.AdvancedFunctionProcess;
import nl.b3p.brmo.loader.jdbc.GeometryJdbcConverter;
import nl.b3p.brmo.loader.jdbc.GeometryJdbcConverterFactory;
import nl.b3p.brmo.loader.util.StagingRowHandler;
import nl.b3p.brmo.loader.xml.BagXMLReader;
import nl.b3p.brmo.loader.xml.BrkSnapshotXMLReader;
import nl.b3p.brmo.service.util.ConfigUtil;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;
import org.apache.commons.dbutils.RowProcessor;
import org.apache.commons.dbutils.handlers.ScalarHandler;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.stripesstuff.plugin.waitpage.WaitPage;

/**
 *
 * @author Chris van Lith
 */
@StrictBinding
public class AdvancedFunctionsActionBean implements ActionBean, ProgressUpdateListener {

    private static final Log log = LogFactory.getLog(AdvancedFunctionsActionBean.class);

    private static final String JSP = "/WEB-INF/jsp/transform/advancedfunctions.jsp";
    private static final String JSP_PROGRESS = "/WEB-INF/jsp/transform/advancedfunctionsprogress.jsp";
    
    private static final String MUTOPEN = "<?xml version=\"1.0\"?><Mutatie:Mutatie "
            + "xmlns:Mutatie=\"http://www.kadaster.nl/schemas/brk-levering/product-mutatie/v20120901\" "
            + "xmlns:KadastraalObjectRef=\"http://www.kadaster.nl/schemas/brk-levering/snapshot/imkad-kadastraalobject-ref/v20120201\" "
            + "xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
            + "<Mutatie:BRKDatum>%s</Mutatie:BRKDatum>"
            + "<Mutatie:volgnummerKadastraalObjectDatum>%s</Mutatie:volgnummerKadastraalObjectDatum>"
            + "<Mutatie:kadastraalObject><Mutatie:AanduidingKadastraalObject><Mutatie:kadastraalObject>"
            + "<KadastraalObjectRef:PerceelRef xlink:href=\"%s\"/>"
            + "</Mutatie:kadastraalObject></Mutatie:AanduidingKadastraalObject></Mutatie:kadastraalObject>"
            + "<Mutatie:wordt>";
    private static final String MUTCLOSE = "</Mutatie:wordt></Mutatie:Mutatie>";

    private ActionBeanContext context;

    private List<AdvancedFunctionProcess> advancedFunctionProcesses;

    @Validate(required = true, on = "perform")
    private String advancedFunctionProcessName;

    private double progress;
    private long total;
    private long processed;
    private boolean complete;
    private Date start;
    private Date update;
    private String exceptionStacktrace;
    
    private final boolean repairFirst = false;

    // <editor-fold defaultstate="collapsed" desc="getters en setters">
    @Override
    public ActionBeanContext getContext() {
        return context;
    }

    @Override
    public void setContext(ActionBeanContext context) {
        this.context = context;
    }

    public double getProgress() {
        return progress;
    }

    public void setProgress(double progress) {
        this.progress = progress;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getProcessed() {
        return processed;
    }

    public void setProcessed(long processed) {
        this.processed = processed;
    }

    public boolean isComplete() {
        return complete;
    }

    public void setComplete(boolean complete) {
        this.complete = complete;
    }

    public Date getStart() {
        return start;
    }

    public void setStart(Date start) {
        this.start = start;
    }

    public Date getUpdate() {
        return update;
    }

    public void setUpdate(Date update) {
        this.update = update;
    }

    public String getExceptionStacktrace() {
        return exceptionStacktrace;
    }

    public void setExceptionStacktrace(String exceptionStacktrace) {
        this.exceptionStacktrace = exceptionStacktrace;
    }

    @Override
    public void total(long total) {
        this.total = total;
    }

    @Override
    public void progress(long progress) {
        this.processed = progress;
        if (this.total != 0) {
            this.progress = (100.0 / this.total) * this.processed;
        }
        this.update = new Date();
    }

    @Override
    public void exception(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        this.exceptionStacktrace = sw.toString();
    }

    public List<AdvancedFunctionProcess> getAdvancedFunctionProcesses() {
        return advancedFunctionProcesses;
    }

    public void setAdvancedFunctionProcesses(List<AdvancedFunctionProcess> advancedFunctionProcesses) {
        this.advancedFunctionProcesses = advancedFunctionProcesses;
    }

    public String getAdvancedFunctionProcessName() {
        return advancedFunctionProcessName;
    }

    public void setAdvancedFunctionProcessName(String advancedFunctionProcessName) {
        this.advancedFunctionProcessName = advancedFunctionProcessName;
    }
    // </editor-fold>

    @Before(stages = LifecycleStage.BindingAndValidation)
    public void populateAdvancedFunctionProcesses() {

        // XXX move to configuration file
        advancedFunctionProcesses = Arrays.asList(new AdvancedFunctionProcess[]{
            new AdvancedFunctionProcess("Exporteren BRK mutaties", BrmoFramework.BR_BRK, "/tmp/brkmutaties"),
            new AdvancedFunctionProcess("Repareren BRK mutaties met status STAGING_NOK", BrmoFramework.BR_BRK, Bericht.STATUS.STAGING_NOK.toString()),
            new AdvancedFunctionProcess("Repareren BAG mutaties met status STAGING_NOK", BrmoFramework.BR_BAG, Bericht.STATUS.STAGING_NOK.toString()),
            new AdvancedFunctionProcess("Opschonen en archiveren van BRK berichten met status RSGB_OK, ouder dan 3 maanden", BrmoFramework.BR_BRK, Bericht.STATUS.RSGB_OK.toString()),
            new AdvancedFunctionProcess("Opschonen en archiveren van BAG berichten met status RSGB_OK, ouder dan 3 maanden", BrmoFramework.BR_BAG, Bericht.STATUS.RSGB_OK.toString()),
            new AdvancedFunctionProcess("Verwijderen van BRK berichten met status ARCHIVE", BrmoFramework.BR_BRK, Bericht.STATUS.ARCHIVE.toString()),
            new AdvancedFunctionProcess("Verwijderen van BAG berichten met status ARCHIVE", BrmoFramework.BR_BAG, Bericht.STATUS.ARCHIVE.toString())
        });
    }

    @DefaultHandler
    public Resolution form() {
        return new ForwardResolution(complete ? JSP_PROGRESS : JSP);
    }

    @WaitPage(path = JSP_PROGRESS, delay = 1000, refresh = 1000)
    public Resolution perform() {
        AdvancedFunctionProcess process = null;

        for (AdvancedFunctionProcess p : advancedFunctionProcesses) {
            if (p.getName().equals(advancedFunctionProcessName)) {
                process = p;
                break;
            }
        }
        if (process == null) {
            getContext().getMessages().add(new SimpleMessage("Ongeldig proces"));
            return new ForwardResolution(JSP);
        }

        start = new Date();
        log.info("Start export process: " + process.getName());
        processed = 0;

        // Get berichten
        try {
            if (process.getName().equals("Exporteren BRK mutaties")) {
                exportBRKMutatieBerichten(process.getConfig());
            } else if (process.getName().equals("Repareren BRK mutaties met status STAGING_NOK")) {
                repairBRKMutatieBerichten(process.getConfig());
            } else if (process.getName().equals("Repareren BAG mutaties met status STAGING_NOK")) {
                repairBAGMutatieBerichten(process.getConfig());
            } else if (process.getName().equals("Opschonen en archiveren van BRK berichten met status RSGB_OK, ouder dan 3 maanden")) {
                cleanupBerichten(process.getConfig(), "brk");
            } else if (process.getName().equals("Opschonen en archiveren van BAG berichten met status RSGB_OK, ouder dan 3 maanden")) {
                cleanupBerichten(process.getConfig(), "bag");
            } else if (process.getName().equals("Verwijderen van BRK berichten met status ARCHIVE")) {
                deleteBerichten(process.getConfig(), "brk");
            } else if (process.getName().equals("Verwijderen van BAG berichten met status ARCHIVE")) {
                deleteBerichten(process.getConfig(), "bag");
            }
            
            if (this.exceptionStacktrace == null) {
                getContext().getMessages().add(new SimpleMessage("Geavanceerde functie afgerond."));
            }
        } catch (Throwable t) {
            log.error("Fout bij geavanceerd functie", t);
            String m = "Fout bij geavanceerd functie: " + ExceptionUtils.getMessage(t);
            if (t.getCause() != null) {
                m += ", oorzaak: " + ExceptionUtils.getRootCauseMessage(t);
            }
            getContext().getMessages().add(new SimpleMessage(m));
        } finally {
            complete = true;
        }
        return new ForwardResolution(JSP_PROGRESS);
    }
    
    public void repairBRKMutatieBerichten(String config) throws Exception {
        int offset = 0;
        int batch = 1000;
        final MutableInt processed = new MutableInt(0);
        final DataSource dataSourceStaging = ConfigUtil.getDataSourceStaging();
        final Connection conn = dataSourceStaging.getConnection();
        final GeometryJdbcConverter geomToJdbc = GeometryJdbcConverterFactory.getGeometryJdbcConverter(conn);
        final RowProcessor processor = new StagingRowHandler();
        
        do {
            log.debug(String.format("Ophalen mutatieberichten batch met offset %d, limit %d", offset, batch));
            String sql = "select * from " + BrmoFramework.BERICHT_TABLE 
                    + " where volgordenummer >= 0 "
                    + " and soort='brk' "
                    + " and status='" + config + "'"
                    + " order by id ";
            sql = geomToJdbc.buildPaginationSql(sql, offset, batch);
            log.debug("SQL voor ophalen berichten batch: " + sql);
            
            
            processed.setValue(0);
            Exception e = new QueryRunner(geomToJdbc.isPmdKnownBroken()).query(conn, sql, new ResultSetHandler<Exception>() {
                @Override
                public Exception handle(ResultSet rs) throws SQLException {

                    while (rs.next()) {
                        try {
                            Bericht bericht = processor.toBean(rs, Bericht.class);
                            StringBuilder message = new StringBuilder();
                            if (bericht.getBrOrgineelXml() != null && !bericht.getBrOrgineelXml().isEmpty()) {
                                message.append(bericht.getBrOrgineelXml());
                            } else {
                                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
                                message.append(String.format(MUTOPEN,
                                        dateFormat.format(bericht.getDatum()),
                                        bericht.getVolgordeNummer(),
                                        bericht.getObjectRef()));
                                if (bericht.getBrXml().startsWith("<?xml version=\"1.0\"")) {
                                    // strip <?xml version="1.0"?>
                                    message.append(bericht.getBrXml().substring(21));
                                } else {
                                    message.append(bericht.getBrXml());
                                }
                                message.append(MUTCLOSE);
                            }

                            BrkSnapshotXMLReader reader = new BrkSnapshotXMLReader(new ByteArrayInputStream(message.toString().getBytes("UTF-8")));
                            Bericht brk = reader.next();
                            if (brk != null && brk.getDatum() != null && brk.getObjectRef() != null && brk.getVolgordeNummer() != null) {
                                bericht.setDatum(brk.getDatum());
                                bericht.setObjectRef(brk.getObjectRef());
                                bericht.setVolgordeNummer(brk.getVolgordeNummer());
                            }
                            if (brk != null && brk.getDatum() != null && brk.getObjectRef() != null && brk.getVolgordeNummer() != null) {
                                if (bericht.getBrOrgineelXml() != null && !bericht.getBrOrgineelXml().isEmpty()) {
                                    new QueryRunner(geomToJdbc.isPmdKnownBroken())
                                            .update(conn, "update " + BrmoFramework.BERICHT_TABLE + " set object_ref = ?, datum = ?, volgordenummer = ? where id = ?",
                                                    bericht.getObjectRef(), new Timestamp(bericht.getDatum().getTime()), bericht.getVolgordeNummer(), bericht.getId());
                                } else {
                                    new QueryRunner(geomToJdbc.isPmdKnownBroken())
                                            .update(conn, "update " + BrmoFramework.BERICHT_TABLE + " set object_ref = ?, datum = ?, volgordenummer = ?, br_orgineel_xml = ? where id = ?",
                                                    bericht.getObjectRef(), new Timestamp(bericht.getDatum().getTime()), bericht.getVolgordeNummer(), message.toString(), bericht.getId());
                                }
                            }
                                
                        } catch (Exception e) {
                            return e;
                        }
                        processed.increment();
                    }
                    return null;
                }
            });
            offset += processed.intValue();

            progress(offset);

            // If handler threw exception processing row, rethrow it
            if (e != null) {
                throw e;
            }
        } while (processed.intValue() > 0);

    }

    public void repairBAGMutatieBerichten(String config) throws Exception {
        int offset = 0;
        int batch = 1000;
        final MutableInt processed = new MutableInt(0);
        final DataSource dataSourceStaging = ConfigUtil.getDataSourceStaging();
        final Connection conn = dataSourceStaging.getConnection();
        final GeometryJdbcConverter geomToJdbc = GeometryJdbcConverterFactory.getGeometryJdbcConverter(conn);
        final RowProcessor processor = new StagingRowHandler();
        
        do {
            log.debug(String.format("Ophalen BAG mutatieberichten batch met offset %d, limit %d", offset, batch));
            String sql = "select * from " + BrmoFramework.BERICHT_TABLE 
                    + " where volgordenummer >= 0 "
                    + " and soort='bag' "
                    + " and status='" + config + "'"
                    + " order by id ";
            sql = geomToJdbc.buildPaginationSql(sql, offset, batch);
            log.debug("SQL voor ophalen berichten batch: " + sql);
            
            
            processed.setValue(0);
            Exception e = new QueryRunner(geomToJdbc.isPmdKnownBroken()).query(conn, sql, new ResultSetHandler<Exception>() {
                @Override
                public Exception handle(ResultSet rs) throws SQLException {

                    while (rs.next()) {
                        try {
                            Bericht bericht = processor.toBean(rs, Bericht.class);

                            StringBuilder message = new StringBuilder();
                            if (bericht.getBrOrgineelXml() != null && !bericht.getBrOrgineelXml().isEmpty()) {
                                message.append(bericht.getBrOrgineelXml());
                            } else {
                                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
                                if (bericht.getBrXml().startsWith("<?xml version=\"1.0\"")) {
                                    // strip <?xml version="1.0"?>
                                    message.append(bericht.getBrXml().substring(21));
                                } else {
                                    message.append(bericht.getBrXml());
                                }
                            }

                            BagXMLReader reader = new BagXMLReader(new ByteArrayInputStream(message.toString().getBytes("UTF-8")));
                            reader.hasNext();
                            Bericht bag = reader.next();
                            if (bag != null && bag.getDatum() != null && bag.getObjectRef() != null && bag.getVolgordeNummer() != null) {
                                bericht.setDatum(bag.getDatum());
                                bericht.setObjectRef(bag.getObjectRef());
                                bericht.setVolgordeNummer(bag.getVolgordeNummer());
                            }
                            if (bag != null && bag.getDatum() != null && bag.getObjectRef() != null && bag.getVolgordeNummer() != null) {
                                if (bericht.getBrOrgineelXml() != null && !bericht.getBrOrgineelXml().isEmpty()) {
                                    new QueryRunner(geomToJdbc.isPmdKnownBroken())
                                            .update(conn, "update " + BrmoFramework.BERICHT_TABLE + " set object_ref = ?, datum = ?, volgordenummer = ? where id = ?",
                                                    bericht.getObjectRef(), new Timestamp(bericht.getDatum().getTime()), bericht.getVolgordeNummer(), bericht.getId());
                                } else {
                                    new QueryRunner(geomToJdbc.isPmdKnownBroken())
                                            .update(conn, "update " + BrmoFramework.BERICHT_TABLE + " set object_ref = ?, datum = ?, volgordenummer = ?, br_orgineel_xml = ? where id = ?",
                                                    bericht.getObjectRef(), new Timestamp(bericht.getDatum().getTime()), bericht.getVolgordeNummer(), message.toString(), bericht.getId());
                                }
                            }
                        } catch (Exception e) {
                            return e;
                        }
                        processed.increment();
                    }
                    return null;
                }
            });
            offset += processed.intValue();

            progress(offset);

            // If handler threw exception processing row, rethrow it
            if (e != null) {
                throw e;
            }
        } while (processed.intValue() > 0);

    }

    public void exportBRKMutatieBerichten(String locatie) throws Exception {
        
        if (repairFirst) {
            repairBRKMutatieBerichten(null);
        }
        
        int offset = 0;
        int batch = 5000;
        final MutableInt processed = new MutableInt(0);
        final DataSource dataSourceStaging = ConfigUtil.getDataSourceStaging();
        final Connection conn = dataSourceStaging.getConnection();
        final GeometryJdbcConverter geomToJdbc = GeometryJdbcConverterFactory.getGeometryJdbcConverter(conn);
        final RowProcessor processor = new StagingRowHandler();

        final File exportDir = new File(locatie);
        FileUtils.forceMkdir(exportDir);

        do {
            log.debug(String.format("Ophalen mutatieberichten batch met offset %d, limit %d", offset, batch));
            String sql = "select * from " + BrmoFramework.BERICHT_TABLE 
                    + " where volgordenummer >= 0 "
                    + " and soort='brk' "
                    + " order by object_ref, datum, volgordenummer ";
            sql = geomToJdbc.buildPaginationSql(sql, offset, batch);
            log.debug("SQL voor ophalen berichten batch: " + sql);

            final File f = new File(exportDir, "batch" + offset + ".zip");
            final ZipOutputStream out = new ZipOutputStream(new FileOutputStream(f));

            processed.setValue(0);
            Exception e = new QueryRunner(geomToJdbc.isPmdKnownBroken()).query(conn, sql, new ResultSetHandler<Exception>() {
                @Override
                public Exception handle(ResultSet rs) throws SQLException {

                    while (rs.next()) {
                        try {
                            Bericht bericht = processor.toBean(rs, Bericht.class);

                            boolean infoOK = true;
                            if (bericht.getBrOrgineelXml() != null) {
                                BrkSnapshotXMLReader reader = new BrkSnapshotXMLReader(new ByteArrayInputStream(bericht.getBrOrgineelXml().getBytes("UTF-8")));
                                Bericht brk = reader.next();
                                if (brk.getDatum() != null && brk.getObjectRef() != null && brk.getVolgordeNummer() != null) {
                                    bericht.setDatum(brk.getDatum());
                                    bericht.setObjectRef(brk.getObjectRef());
                                    bericht.setVolgordeNummer(brk.getVolgordeNummer());
                                } else {
                                    infoOK = false;
                                }
                            } else {
                                infoOK = false;
                            }
                            StringBuilder sb = new StringBuilder();
                            if (!infoOK) {
                                sb.append("I");
                            }
                            if (bericht.getObjectRef() != null) {
                                //substring om NL.KAD.OnroerendeZaak: eraf te strippen
                                sb.append(bericht.getObjectRef().substring(22));
                            } else {
                                sb.append((new Date()).getTime());
                            }
                            sb.append("_");
                            if (bericht.getDatum() != null) {
                                sb.append(bericht.getDatum().getTime());
                            } else {
                                sb.append("O");
                                sb.append((new Date()).getTime());
                            }
                            sb.append("_");
                            sb.append(bericht.getVolgordeNummer());
                            sb.append(".xml");

                            ZipEntry e = new ZipEntry(sb.toString());
                            try {
                                out.putNextEntry(e);
                                byte[] data = null;
                                if (infoOK) {
                                    data = bericht.getBrOrgineelXml().getBytes("utf-8");
                                } else {
                                    data = "ERROR".getBytes("utf-8");
                                }
                                out.write(data, 0, data.length);
                            } catch (ZipException ze) {
                                log.info(ze.getLocalizedMessage());
                            } finally {
                                out.closeEntry();
                            }
                         } catch (Exception e) {
                            return e;
                        }
                        processed.increment();
                    }
                    return null;
                }
            });
            if (out != null) {
                out.close();
            }
            offset += processed.intValue();

            progress(offset);

            // If handler threw exception processing row, rethrow it
            if (e != null) {
                throw e;
            }
        } while (processed.intValue() > 0);

    }
    
    public void cleanupBerichten(String config, String soort) throws Exception {
        int offset = 0;
        int batch = 1000;
        final MutableInt processed = new MutableInt(0);
        final DataSource dataSourceStaging = ConfigUtil.getDataSourceStaging();
        final Connection conn = dataSourceStaging.getConnection();
        final GeometryJdbcConverter geomToJdbc = GeometryJdbcConverterFactory.getGeometryJdbcConverter(conn);
        final RowProcessor processor = new StagingRowHandler();
        
        Calendar c = Calendar.getInstance();
        c.setTime(new Date());
        c.add(Calendar.MONTH, -1);
     
        String countsql = "select count(*) from " + BrmoFramework.BERICHT_TABLE 
                    + " where soort='" + soort + "' "
                    + " and status='" + config + "'"
                    + " and status_datum < ? ";
        Object o = new QueryRunner(geomToJdbc.isPmdKnownBroken()).query(conn,
                countsql, new Timestamp(c.getTimeInMillis()), new ScalarHandler());
        if (o instanceof BigDecimal) {
            total(((BigDecimal) o).longValue());
        } else if (o instanceof Integer) {
            total(((Integer) o).longValue());
        } else {
            total((Long) o);
        }
        
        do {
            log.debug(String.format("Ophalen berichten batch met offset %d, limit %d", offset, batch));
            String sql = "select * from " + BrmoFramework.BERICHT_TABLE 
                    + " where soort='" + soort + "' "
                    + " and status='" + config + "'"
                    + " and status_datum < ? "
                    + " order by id ";
            sql = geomToJdbc.buildPaginationSql(sql, offset, batch);
            log.debug("SQL voor ophalen berichten batch: " + sql);
            
            processed.setValue(0);
            Exception e = new QueryRunner(geomToJdbc.isPmdKnownBroken()).query(conn, sql, new Timestamp(c.getTimeInMillis()), new ResultSetHandler<Exception>() {
                @Override
                public Exception handle(ResultSet rs) throws SQLException {

                    while (rs.next()) {
                        try {
                            Bericht bericht = processor.toBean(rs, Bericht.class);

                            bericht.setBrOrgineelXml("opgeschoond");
                            bericht.setBrXml("opgeschoond");
                            bericht.setOpmerking("opgeschoond, status was: " + bericht.getStatus().toString());
                            bericht.setStatus(Bericht.STATUS.ARCHIVE);
                            bericht.setStatusDatum(new Date());

                            new QueryRunner(geomToJdbc.isPmdKnownBroken())
                                    .update(conn, "update " + BrmoFramework.BERICHT_TABLE 
                                            + " set status_datum = ?, status = ?, opmerking = ?, br_xml = ?, br_orgineel_xml = ? where id = ?",
                                            new Timestamp(bericht.getDatum().getTime()), bericht.getStatus().toString(), bericht.getOpmerking(), 
                                            bericht.getBrXml(), bericht.getBrOrgineelXml(), bericht.getId());
                                 
                        } catch (Exception e) {
                            return e;
                        }
                        processed.increment();
                    }
                    return null;
                }
            });
            offset += processed.intValue();

            progress(offset);

            // If handler threw exception processing row, rethrow it
            if (e != null) {
                throw e;
            }
        } while (processed.intValue() > 0);

    }

    public void deleteBerichten(String config, String soort) throws Exception {
        final MutableInt processed = new MutableInt(0);
        final DataSource dataSourceStaging = ConfigUtil.getDataSourceStaging();
        final Connection conn = dataSourceStaging.getConnection();
        final GeometryJdbcConverter geomToJdbc = GeometryJdbcConverterFactory.getGeometryJdbcConverter(conn);
        
        String countsql = "select count(*) from " + BrmoFramework.BERICHT_TABLE 
                    + " where soort='" + soort + "' "
                    + " and status='" + config + "'";
        Object o = new QueryRunner(geomToJdbc.isPmdKnownBroken()).query(conn,
                countsql, new ScalarHandler());
        if (o instanceof BigDecimal) {
            total(((BigDecimal) o).longValue());
        } else if (o instanceof Integer) {
            total(((Integer) o).longValue());
        } else {
            total((Long) o);
        }
        
        o = new QueryRunner(geomToJdbc.isPmdKnownBroken()).update(conn, "DELETE FROM " + BrmoFramework.BERICHT_TABLE
                + " WHERE soort='" + soort + "' "
                + " and status='" + config + "'");
        
        if (o instanceof BigDecimal) {
            progress(((BigDecimal) o).longValue());
        } else if (o instanceof Integer) {
            progress(((Integer) o).longValue());
        } else {
            progress((Long) o);
        }

    }

}
