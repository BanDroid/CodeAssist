package com.tyron.completion.xml.insert;

import com.android.ide.common.rendering.api.AttrResourceValue;
import com.android.ide.common.rendering.api.AttributeFormat;
import com.tyron.completion.model.CompletionItem;
import com.tyron.editor.Caret;
import com.tyron.editor.Editor;

public class ValueInsertHandler extends DefaultXmlInsertHandler {

    private final AttrResourceValue attributeInfo;

    public ValueInsertHandler(AttrResourceValue attributeInfo, CompletionItem item) {
        super(item);

        this.attributeInfo = attributeInfo;
    }

    @Override
    protected void insert(String string, Editor editor, boolean calcSpace) {
        super.insert(string, editor, calcSpace);

        Caret caret = editor.getCaret();
        int line = caret.getStartLine();
        int column = caret.getStartColumn();
        if (!attributeInfo.getFormats().contains(AttributeFormat.FLAGS)) {
            String lineString = editor.getContent().getLineString(line);
            if (lineString.charAt(column) == '"') {
                editor.setSelection(line, column + 1);
                editor.insertMultilineString(line, column + 1, "\n");
            }
        }
    }
}
