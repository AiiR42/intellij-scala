package org.jetbrains.plugins.hocon.settings;

import com.intellij.openapi.project.Project;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;

import javax.swing.*;
import java.awt.*;

public class HoconProjectSettingsPanel {
  private final Project project;

  private JPanel mainPanel;
  private JCheckBox classReferencesUnquotedCheckBox;
  private JCheckBox classReferencesQuotedCheckBox;

  public HoconProjectSettingsPanel(Project project) {
    this.project = project;
    loadSettings();
  }

  public void loadSettings() {
    HoconProjectSettings settings = HoconProjectSettings.getInstance(project);
    classReferencesUnquotedCheckBox.setSelected(settings.getClassReferencesOnUnquotedStrings());
    classReferencesQuotedCheckBox.setSelected(settings.getClassReferencesOnQuotedStrings());
  }

  public JComponent getMainComponent() {
    return mainPanel;
  }

  public boolean isModified() {
    HoconProjectSettings settings = HoconProjectSettings.getInstance(project);
    return classReferencesUnquotedCheckBox.isSelected() != settings.getClassReferencesOnUnquotedStrings() ||
        classReferencesQuotedCheckBox.isSelected() != settings.getClassReferencesOnQuotedStrings();
  }

  public void apply() {
    HoconProjectSettings settings = HoconProjectSettings.getInstance(project);
    settings.setClassReferencesOnUnquotedStrings(classReferencesUnquotedCheckBox.isSelected());
    settings.setClassReferencesOnQuotedStrings(classReferencesQuotedCheckBox.isSelected());
  }

  {
// GUI initializer generated by IntelliJ IDEA GUI Designer
// >>> IMPORTANT!! <<<
// DO NOT EDIT OR ADD ANY CODE HERE!
    $$$setupUI$$$();
  }

  /**
   * Method generated by IntelliJ IDEA GUI Designer
   * >>> IMPORTANT!! <<<
   * DO NOT edit this method OR call it in your code!
   *
   * @noinspection ALL
   */
  private void $$$setupUI$$$() {
    mainPanel = new JPanel();
    mainPanel.setLayout(new GridLayoutManager(4, 2, new Insets(0, 0, 0, 0), -1, -1));
    final JLabel label1 = new JLabel();
    label1.setText("Detect class references in:");
    mainPanel.add(label1, new GridConstraints(0, 0, 1, 2, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    classReferencesUnquotedCheckBox = new JCheckBox();
    classReferencesUnquotedCheckBox.setText("Unquoted strings");
    mainPanel.add(classReferencesUnquotedCheckBox, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    classReferencesQuotedCheckBox = new JCheckBox();
    classReferencesQuotedCheckBox.setText("Quoted strings");
    mainPanel.add(classReferencesQuotedCheckBox, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    final Spacer spacer1 = new Spacer();
    mainPanel.add(spacer1, new GridConstraints(3, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1, GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
  }

  /**
   * @noinspection ALL
   */
  public JComponent $$$getRootComponent$$$() {
    return mainPanel;
  }
}
