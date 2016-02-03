/*
 * @(#)RS422VideoIO.java   by Brian Schlining
 *
 * Copyright (c) 2016 Monterey Bay Aquarium Research Institute
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mbari.vcr4j.rs422;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.mbari.util.NumberUtilities;
import org.mbari.vcr4j.VideoCommand;
import org.mbari.vcr4j.VideoIO;
import org.mbari.vcr4j.VideoIndex;
import org.mbari.vcr4j.commands.VideoCommands;
import org.mbari.vcr4j.rs422.commands.CommandToBytes;
import org.mbari.vcr4j.rs422.commands.RS422ByteCommands;
import org.mbari.vcr4j.rs422.commands.RS422VideoCommands;
import org.mbari.vcr4j.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.SerializedSubject;
import rx.subjects.Subject;

/**
 * @author Brian Schlining
 * @since 2016-01-29T16:42:00
 */
public abstract class RS422VideoIO implements VideoIO<RS422State, RS422Error> {


    private final long ioDelay;
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final RS422ResponseParser responseParser = new RS422ResponseParser();
    private final Subject<VideoCommand, VideoCommand> commandSubject = new SerializedSubject<>(PublishSubject.create());
    private OutputStream outputStream;    // Sends commands to VCR
    private InputStream inputStream;      // Reads responses from VCR

    /**
     *
     * @param inputStream The serialPorts inputstream
     * @param outputStream The serialPorts outputstream
     * @param ioDelay The amount of millisecs to wait between request and responses. RXTX seems to need about 10
     *                Purejavacomm about 100.
     */
    public RS422VideoIO(InputStream inputStream, OutputStream outputStream, long ioDelay) {
        Preconditions.checkArgument(inputStream != null, "InputStream can not be null");
        Preconditions.checkArgument(outputStream != null, "OutputStream can not be null");
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.ioDelay = ioDelay;

        commandSubject.subscribe(vc -> {
                if (vc.equals(RS422VideoCommands.REQUEST_USERBITS)) {

                    // LUB and VUB access if very state dependant. We get around that by
                    // requesting for both.
                    send(RS422VideoCommands.REQUEST_LUSERBITS);
                    send(RS422VideoCommands.REQUEST_VUSERBITS);
                }
                else if (vc.equals(VideoCommands.REQUEST_TIMESTAMP)
                         || vc.equals(VideoCommands.REQUEST_ELAPSED_TIME)) {

                    // Do Nothing
                }
                else {
                    byte[] cmd = CommandToBytes.apply(vc);

                    if (!Arrays.equals(cmd, RS422ByteCommands.UNDEFINED.getBytes())) {
                        sendCommand(cmd, vc);
                    }
                }

            });
    }

    private void logCommand(byte[] bytes, VideoCommand videoCommand) {
        if (log.isDebugEnabled()) {
            log.debug("[0x" + NumberUtilities.toHexString(bytes) + "] >>> VCR (" + videoCommand.getName() + ")");
        }
    }

    private void logResponse(byte[] cmd, byte[] data, byte[] checksum) {
        if (log.isDebugEnabled()) {

            /*
             * Munge it all into a single byte array
             */
            int dataLength = (data == null) ? 0 : data.length;
            final byte[] c = new byte[cmd.length + dataLength + 1];

            System.arraycopy(cmd, 0, c, 0, cmd.length);

            if (data != null) {
                System.arraycopy(data, 0, c, cmd.length, data.length);
            }

            c[c.length - 1] = checksum[0];

            log.debug("[0x" + NumberUtilities.toHexString(c) + "] <<< VCR");
        }
    }

    /**
     * Reads the response to a command from the serial port connected to the VCR.
     * @throws IOException
     * @throws RS422Exception
     * @throws InterruptedException
     */
    protected synchronized void readResponse(byte[] mostRecentCommand, VideoCommand videoCommand)
            throws IOException, RS422Exception, InterruptedException {

        // Get the command returned by the VCR
        final byte[] cmd = new byte[2];

        if (inputStream.available() > 0) {
            inputStream.read(cmd);
        }

        Thread.sleep(ioDelay);    // RXTX does not block serial IO correctly.

        // Extract the number of data bytes in the command block. Then
        // read the data from the serial port
        final int numDataBytes = (int) (cmd[0] & 0x0F);    // Get the number of data blocks
        byte[] data = null;

        if (numDataBytes > 0) {
            data = new byte[numDataBytes];

            if (inputStream.available() > 0) {
                inputStream.read(data);
            }
            else {
                throw new IOException("Incoming data is missing . byte[] = " + NumberUtilities.toHexString(cmd));
            }
        }

        Thread.sleep(ioDelay);    // RXTX does not block serial IO correctly.

        // Read the checksum that the VCR sends
        final byte[] checksum = new byte[1];

        if (inputStream.available() > 0) {
            inputStream.read(checksum);
        }
        else {
            throw new IOException("Incoming checksum is missing. cmd[] =  " + NumberUtilities.toHexString(cmd)
                                  + " data[] = " + NumberUtilities.toHexString(data));
        }

        logResponse(cmd, data, checksum);

        responseParser.update(mostRecentCommand, cmd, data, checksum, Optional.of(videoCommand));

    }

    @Override
    public <A extends VideoCommand> void send(A videoCommand) {
        commandSubject.onNext(videoCommand);
    }

    /**
     * Sends a command, in the format of a byte[], to the VCR.
     * @param command The command to send to the VCR
     */
    protected synchronized void sendCommand(byte[] command, VideoCommand videoCommand) {

        // Add the checksum
        byte checksum = RS422ResponseParser.calculateChecksum(command);

        command[command.length - 1] = checksum;

        try {
            logCommand(command, videoCommand);
            outputStream.write(command);
            Thread.sleep(ioDelay);    // RXTX does not block serial IO correctly.
            readResponse(command, videoCommand);
        }
        catch (IOException | RS422Exception e) {
            log.error("Failed to send a command to the VCR", e);
        }
        catch (InterruptedException e) {
            log.error("Thread " + Thread.currentThread().getName() + " was interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Subject<VideoCommand, VideoCommand> getCommandSubject() {
        return commandSubject;
    }

    @Override
    public Observable<RS422Error> getErrorObservable() {
        return responseParser.getErrorObservable();
    }

    /**
     *
     * @return An Observable for the VideoIndex. If the device is recording, the VideoIndex will
     *    also have a timestamp from the computers clock as well as a timecode. Otherwise,
     *    only a timecode will be included.
     */
    @Override
    public Observable<VideoIndex> getIndexObservable() {
        return Observable
                .combineLatest(responseParser.getTimecodeObservable(),
                               responseParser.getStatusObservable(),
                               (timecode, state) -> {
                                   if (state.isRecording()) {
                                       return new VideoIndex(Optional.of(Instant.now()),
                                                             Optional.empty(),
                                                             Optional.of(timecode.getTimecode()));
                                   }
                                   else {
                                       return new VideoIndex(Optional.empty(),
                                                             Optional.empty(),
                                                             Optional.of(timecode.getTimecode()));
                                   }
                               })
                .distinctUntilChanged();
    }

    protected InputStream getInputStream() {
        return inputStream;
    }

    protected OutputStream getOutputStream() {
        return outputStream;
    }

    protected RS422ResponseParser getResponseParser() {
        return responseParser;
    }

    @Override
    public Observable<RS422State> getStateObservable() {
        return responseParser.getStatusObservable();
    }

    public Observable<RS422Timecode> getTimecodeObservable() {
        return responseParser.getTimecodeObservable();
    }

    public Observable<RS422Userbits> getUserbitsObservable() {
        return responseParser.getUserbitsObservable();
    }
}
