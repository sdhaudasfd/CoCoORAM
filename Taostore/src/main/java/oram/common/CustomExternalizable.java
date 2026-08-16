package oram.common;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public interface CustomExternalizable {
	void writeExternal(DataOutput out) throws IOException;

	void readExternal(DataInput in) throws IOException;
}
