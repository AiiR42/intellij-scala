package org.jetbrains.plugins.scala.worksheet.ui.dialog;

import com.intellij.application.options.ModulesComboBox;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import org.jetbrains.plugins.scala.project.settings.ScalaCompilerSettingsProfile;
import org.jetbrains.plugins.scala.worksheet.settings.WorksheetCommonSettings;
import org.jetbrains.plugins.scala.worksheet.settings.WorksheetExternalRunType;
import org.jetbrains.plugins.scala.worksheet.settings.WorksheetFileSettings;
import org.jetbrains.plugins.scala.worksheet.settings.WorksheetProjectSettings;

import javax.swing.*;
import java.awt.*;

public class WorksheetSettingsSetForm {
    private PsiFile myFile;
    private Project myProject;

    private JCheckBox interactiveModeCheckBox;
    private JCheckBox makeProjectBeforeRunCheckBox;
    private ModulesComboBox moduleComboBox;
    private JPanel mainPanel;
    private JComboBox<ScalaCompilerSettingsProfile> compilerProfileComboBox;
    private ActionButton openCompilerProfileSettingsButton;
    private JComboBox<WorksheetExternalRunType> runTypeComboBox;

    WorksheetSettingsSetForm(PsiFile file, WorksheetSettingsData settingsData) {
        myFile = file;
        myProject = file.getProject();
        $$$setupUI$$$();
        init(settingsData);
    }

    WorksheetSettingsSetForm(Project project, WorksheetSettingsData settingsData) {
        myFile = null;
        myProject = project;
        $$$setupUI$$$();
        init(settingsData);
    }

    private void init(WorksheetSettingsData settingsData) {
        runTypeComboBox.setModel(new DefaultComboBoxModel<>(WorksheetExternalRunType.getAllRunTypes()));
        runTypeComboBox.setSelectedItem(settingsData.runType);

        interactiveModeCheckBox.setSelected(settingsData.isInteractive);
        makeProjectBeforeRunCheckBox.setSelected(settingsData.isMakeBeforeRun);
        compilerProfileComboBox.setModel(new DefaultComboBoxModel<>(settingsData.profiles));
        compilerProfileComboBox.setSelectedItem(settingsData.compilerProfile);
    }

    public JPanel getMainPanel() {
        return mainPanel;
    }

    public PsiFile getFile() {
        return myFile;
    }

    public WorksheetExternalRunType getRunType() {
        return (WorksheetExternalRunType) runTypeComboBox.getSelectedItem();
    }

    public void onProfilesReload(ScalaCompilerSettingsProfile compilerProfile, ScalaCompilerSettingsProfile[] profiles) {
        compilerProfileComboBox.setSelectedItem(null);
        compilerProfileComboBox.setModel(new DefaultComboBoxModel<>(profiles));
        compilerProfileComboBox.setSelectedItem(compilerProfile);
    }

    public WorksheetSettingsData getSettings() {
        return new WorksheetSettingsData(
                interactiveModeCheckBox.isSelected(),
                makeProjectBeforeRunCheckBox.isSelected(),
                (WorksheetExternalRunType) runTypeComboBox.getSelectedItem(),
                moduleComboBox.isEnabled() ? moduleComboBox.getSelectedModule() : null,
                (ScalaCompilerSettingsProfile) compilerProfileComboBox.getSelectedItem(),
                null
        );
    }

    private void createUIComponents() {
        moduleComboBox = new ModulesComboBox();
        moduleComboBox.fillModules(myProject);
        moduleComboBox.setToolTipText("Using class path of the module...");

        WorksheetCommonSettings settings = myFile != null ?
            WorksheetFileSettings.apply(myFile) :
            WorksheetProjectSettings.apply(myProject);

        Module defaultModule = settings.getModuleFor();
        if (defaultModule != null) {
            moduleComboBox.setSelectedModule(defaultModule);
            boolean enabled = myFile == null || WorksheetFileSettings.isScratchWorksheet(myFile.getVirtualFile(), myFile.getProject());
            moduleComboBox.setEnabled(enabled);
        }

        openCompilerProfileSettingsButton = new ShowCompilerProfileSettingsButton(this).getActionButton();
    }

    /**
     * Method generated by IntelliJ IDEA GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     *
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        createUIComponents();
        mainPanel = new JPanel();
        mainPanel.setLayout(new GridLayoutManager(6, 3, new Insets(0, 0, 0, 0), -1, -1));
        final Spacer spacer1 = new Spacer();
        mainPanel.add(spacer1, new GridConstraints(5, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1, GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        interactiveModeCheckBox = new JCheckBox();
        interactiveModeCheckBox.setText("Interactive Mode");
        mainPanel.add(interactiveModeCheckBox, new GridConstraints(0, 0, 1, 2, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        makeProjectBeforeRunCheckBox = new JCheckBox();
        makeProjectBeforeRunCheckBox.setText("Make project before run");
        mainPanel.add(makeProjectBeforeRunCheckBox, new GridConstraints(1, 0, 1, 2, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        mainPanel.add(moduleComboBox, new GridConstraints(3, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JLabel label1 = new JLabel();
        label1.setText("Use class path of module:");
        mainPanel.add(label1, new GridConstraints(3, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        compilerProfileComboBox = new JComboBox();
        mainPanel.add(compilerProfileComboBox, new GridConstraints(4, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JLabel label2 = new JLabel();
        label2.setText("Compiler profile:");
        mainPanel.add(label2, new GridConstraints(4, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        mainPanel.add(openCompilerProfileSettingsButton, new GridConstraints(4, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        runTypeComboBox = new JComboBox();
        mainPanel.add(runTypeComboBox, new GridConstraints(2, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JLabel label3 = new JLabel();
        label3.setText("Run type:");
        mainPanel.add(label3, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return mainPanel;
    }

}
