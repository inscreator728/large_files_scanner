import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.ArrayList;

public class LargeFileManager extends JFrame {
    private JTable fileTable;
    private FileTableModel tableModel;
    private JProgressBar progressBar;
    private JButton scanButton, deleteButton, browseFolderButton;
    private JTextArea logArea;
    private JScrollPane logScroll;
    private JDialog detailDialog;
    private List<String> logEntries = new ArrayList<>();

    // UI for scan options
    private JComboBox<String> driveCombo;
    private JRadioButton fullScanRadio, driveScanRadio, folderScanRadio;
    private ButtonGroup scanModeGroup;
    private File folderToScan;

    // Threshold for large files: 100 MB
    private static final long SIZE_THRESHOLD = 100L * 1024 * 1024;

    public LargeFileManager() {
        super("High-Tech Large File Manager");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1000, 650);
        setLocationRelativeTo(null);
        initLookAndFeel();
        initUI();
    }

    private void initLookAndFeel() {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception e) {
            // fallback default
        }
    }

    private void initUI() {
        // Top panel: scan mode selection
        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        fullScanRadio = new JRadioButton("Full Scan", true);
        driveScanRadio = new JRadioButton("Drive Scan");
        folderScanRadio = new JRadioButton("Folder Scan");
        scanModeGroup = new ButtonGroup();
        scanModeGroup.add(fullScanRadio);
        scanModeGroup.add(driveScanRadio);
        scanModeGroup.add(folderScanRadio);
        modePanel.add(fullScanRadio);
        modePanel.add(driveScanRadio);
        modePanel.add(folderScanRadio);

        // Drive selection combo
        driveCombo = new JComboBox<>();
        for (File root : File.listRoots()) {
            driveCombo.addItem(root.getAbsolutePath());
        }
        driveCombo.setEnabled(false);
        modePanel.add(new JLabel("Select Drive:"));
        modePanel.add(driveCombo);

        // Folder browse button
        browseFolderButton = new JButton("Browse Folder...");
        browseFolderButton.setEnabled(false);
        browseFolderButton.addActionListener(e -> chooseFolder());
        modePanel.add(browseFolderButton);

        // Buttons
        scanButton = new JButton("Scan");
        scanButton.addActionListener(e -> startScan());
        deleteButton = new JButton("Delete Selected");
        deleteButton.setForeground(Color.RED);
        deleteButton.setEnabled(false);
        deleteButton.addActionListener(e -> confirmAndDelete());
        modePanel.add(scanButton);
        modePanel.add(deleteButton);

        // Table
        tableModel = new FileTableModel();
        fileTable = new JTable(tableModel);
        fileTable.setAutoCreateRowSorter(true);
        fileTable.setRowHeight(24);
        JScrollPane tableScroll = new JScrollPane(fileTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Large Files ( > 100MB )"));

        // Progress bar
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);

        // Log area
        logArea = new JTextArea();
        logArea.setEditable(false);
        logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(800, 300));

        // Listen to mode changes
        ItemListener modeListener = e -> {
            boolean driveMode = driveScanRadio.isSelected();
            boolean folderMode = folderScanRadio.isSelected();
            driveCombo.setEnabled(driveMode);
            browseFolderButton.setEnabled(folderMode);
        };
        fullScanRadio.addItemListener(modeListener);
        driveScanRadio.addItemListener(modeListener);
        folderScanRadio.addItemListener(modeListener);

        // Layout
        Container c = getContentPane();
        c.setLayout(new BorderLayout(10, 10));
        c.add(modePanel, BorderLayout.NORTH);
        c.add(tableScroll, BorderLayout.CENTER);
        c.add(progressBar, BorderLayout.SOUTH);
    }

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            folderToScan = chooser.getSelectedFile();
        }
    }

    private void startScan() {
        scanButton.setEnabled(false);
        deleteButton.setEnabled(false);
        tableModel.clear();
        logEntries.clear();

        SwingWorker<Void, FileRecord> scanner = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                List<Path> roots = new ArrayList<>();
                if (driveScanRadio.isSelected()) {
                    roots.add(Paths.get(driveCombo.getSelectedItem().toString()));
                } else if (folderScanRadio.isSelected() && folderToScan != null) {
                    roots.add(folderToScan.toPath());
                } else {
                    // Full scan
                    for (File r : File.listRoots())
                        roots.add(r.toPath());
                }
                int total = roots.size();
                for (int i = 0; i < roots.size(); i++) {
                    scanDirectory(roots.get(i));
                    setProgress((int) ((i + 1) * 100.0 / total));
                }
                return null;
            }

            private void scanDirectory(Path path) {
                try {
                    Files.walkFileTree(path, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (attrs.size() >= SIZE_THRESHOLD) {
                                publish(new FileRecord(file.toFile(), attrs.size()));
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (IOException ignored) {
                }
            }

            @Override
            protected void process(List<FileRecord> chunks) {
                for (FileRecord rec : chunks)
                    tableModel.addRecord(rec);
            }

            @Override
            protected void done() {
                progressBar.setValue(0);
                scanButton.setEnabled(true);
                deleteButton.setEnabled(tableModel.getRowCount() > 0);
                logEntries.add("Scan complete: " + tableModel.getRowCount() + " files found.");
            }
        };
        scanner.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                progressBar.setValue((Integer) evt.getNewValue());
            }
        });
        scanner.execute();
    }

    private void confirmAndDelete() {
        int[] sel = fileTable.getSelectedRows();
        if (sel.length == 0) {
            JOptionPane.showMessageDialog(this, "No files selected.");
            return;
        }
        List<File> toDel = new ArrayList<>();
        for (int r : sel)
            toDel.add(tableModel.getRecord(fileTable.convertRowIndexToModel(r)).file);

        int y = JOptionPane.showConfirmDialog(this,
                "Delete " + toDel.size() + " files?", "Confirm Deletion",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (y != JOptionPane.YES_OPTION)
            return;

        SwingWorker<Void, String> deleter = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                int total = toDel.size();
                for (int i = 0; i < total; i++) {
                    File f = toDel.get(i);
                    boolean del = false;
                    try {
                        // C: drive permission prompt
                        if (f.getAbsolutePath().toUpperCase().startsWith("C:")) {
                            int ok = JOptionPane.showConfirmDialog(null,
                                    "Allow delete on C: for " + f.getName() + "?",
                                    "Permission", JOptionPane.YES_NO_OPTION);
                            if (ok != JOptionPane.YES_OPTION) {
                                publish("Skipped: " + f.getAbsolutePath());
                                logEntries.add("Skipped: " + f.getAbsolutePath());
                                continue;
                            }
                        }
                        del = f.delete();
                        if (!del) {
                            Process p = new ProcessBuilder("cmd", "/c", "del", "/f", "/q", f.getAbsolutePath()).start();
                            p.waitFor();
                            del = !f.exists();
                        }
                        publish((del ? "Deleted: " : "Failed: ") + f.getAbsolutePath());
                        logEntries.add((del ? "Deleted: " : "Failed: ") + f.getAbsolutePath());
                    } catch (Exception ex) {
                        publish("Error: " + f.getAbsolutePath() + " - " + ex.getMessage());
                        logEntries.add("Error: " + f.getAbsolutePath() + " - " + ex.getMessage());
                    }
                    setProgress((int) ((i + 1) * 100.0 / total));
                }
                return null;
            }

            @Override
            protected void process(List<String> msgs) {
                for (String m : msgs)
                    logArea.append(m + "\n");
            }

            @Override
            protected void done() {
                progressBar.setValue(100);
                showCompletionDialog();
            }
        };
        detailDialog = new JDialog(this, "Details", true);
        detailDialog.getContentPane().add(logScroll);
        detailDialog.pack();
        detailDialog.setLocationRelativeTo(this);

        deleter.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName()))
                progressBar.setValue((Integer) evt.getNewValue());
        });
        deleter.execute();
    }

    private void showCompletionDialog() {
        Object[] opts = { "OK", "Details" };
        int c = JOptionPane.showOptionDialog(this,
                "Deletion completed.", "Done",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                null, opts, opts[0]);
        if (c == 1) {
            logArea.setText(String.join("\n", logEntries));
            detailDialog.setVisible(true);
        }
        progressBar.setValue(0);
        tableModel.clearSelection();
        deleteButton.setEnabled(false);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LargeFileManager().setVisible(true));
    }

    // --- Internal Classes ---
    private static class FileRecord {
        File file;
        long size;

        FileRecord(File f, long s) {
            file = f;
            size = s;
        }
    }

    private static class FileTableModel extends AbstractTableModel {
        private final String[] cols = { "Select", "Path", "Size (MB)", "Drive" };
        private final List<FileRecord> data = new ArrayList<>();
        private final List<Boolean> sel = new ArrayList<>();

        public void addRecord(FileRecord r) {
            data.add(r);
            sel.add(false);
            fireTableRowsInserted(data.size() - 1, data.size() - 1);
        }

        public FileRecord getRecord(int i) {
            return data.get(i);
        }

        @Override
        public int getRowCount() {
            return data.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 0;
        }

        @Override
        public Object getValueAt(int r, int c) {
            FileRecord rcd = data.get(r);
            switch (c) {
                case 0:
                    return sel.get(r);
                case 1:
                    return rcd.file.getAbsolutePath();
                case 2:
                    return String.format("%.2f", rcd.size / (1024.0 * 1024.0));
                case 3:
                    return rcd.file.getAbsolutePath().substring(0, 2);
                default:
                    return null;
            }
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            if (c == 0)
                sel.set(r, (Boolean) v);
            fireTableCellUpdated(r, c);
        }

        public void clear() {
            int n = data.size();
            data.clear();
            sel.clear();
            if (n > 0)
                fireTableRowsDeleted(0, n - 1);
        }

        public void clearSelection() {
            for (int i = 0; i < sel.size(); i++)
                sel.set(i, false);
            fireTableRowsUpdated(0, data.size() - 1);
        }
    }
}
