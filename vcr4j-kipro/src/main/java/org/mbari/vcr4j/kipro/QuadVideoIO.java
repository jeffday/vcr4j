package org.mbari.vcr4j.kipro;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.mbari.vcr4j.VideoCommand;
import org.mbari.vcr4j.VideoIO;
import org.mbari.vcr4j.VideoIndex;
import org.mbari.vcr4j.commands.InjectVideoIndexCmd;
import org.mbari.vcr4j.commands.VideoCommands;
import org.mbari.vcr4j.kipro.commands.QuadVideoCommands;
import org.mbari.vcr4j.kipro.json.ConfigEvent;
import org.mbari.vcr4j.kipro.json.ConnectionID;
import org.mbari.vcr4j.kipro.json.Constants;
import org.mbari.vcr4j.time.Timecode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Scheduler;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subjects.SerializedSubject;
import rx.subjects.Subject;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class that supports reading timecode from the kipro quad. This videoio also supports the
 * `InjectVideoIndexCmd`
 * @author Brian Schlining
 * @since 2016-02-04T11:16:00
 */
public class QuadVideoIO implements VideoIO<QuadState, QuadError> {

    /** Always ends with "/" */
    private final String httpAddress;
    private final AtomicInteger connectionID = new AtomicInteger(0);
    private static final Logger log = LoggerFactory.getLogger(QuadVideoIO.class);
    private final QuadError noError = new QuadError(false, false, Optional.empty(), Optional.empty());


    private final Subject<QuadError, QuadError> errorObservable = new SerializedSubject<>(PublishSubject.create());
    private final Subject<QuadState, QuadState> statusObservable = new SerializedSubject<>(PublishSubject.create());
    private final Subject<Timecode, Timecode> timecodeObservable =
            new SerializedSubject<>(PublishSubject.create());
    private final Subject<VideoIndex, VideoIndex> indexObservable =
            new SerializedSubject<>(PublishSubject.create());
    private final Subject<ConfigEvent[], ConfigEvent[]> configEventObservable =
            new SerializedSubject<>(PublishSubject.create());

    private final Subject<VideoCommand, VideoCommand> commandSubject = new SerializedSubject<>(PublishSubject.create());

    public QuadVideoIO(String httpAddress) {
        this.httpAddress = httpAddress.endsWith("/") ? httpAddress : httpAddress + "/";
        statusObservable.map(QuadState::getConnectionID)
                .forEach(connectionID::set);

        commandSubject.filter(vc -> vc.equals(QuadVideoCommands.CONNECT))
                .forEach(vc -> connect());

        // --- Filter for timecode requests and config events
        commandSubject.filter(vc -> vc.equals(QuadVideoCommands.CONFIG_EVENT)
                || vc.equals(VideoCommands.REQUEST_INDEX)
                || vc.equals(VideoCommands.REQUEST_TIMECODE))
                .forEach(vc -> requestConfigEvent());

        // --- Filter for timestamp requests
        commandSubject.filter(vc -> vc.equals(VideoCommands.REQUEST_TIMESTAMP))
                .forEach(vc -> {
                    VideoIndex videoIndex = new VideoIndex(Optional.of(Instant.now()), Optional.empty(), Optional.empty());
                    indexObservable.onNext(videoIndex);
                });

        // --- We occasionally will need to inject a video index
        commandSubject.ofType(InjectVideoIndexCmd.class)
                .map(InjectVideoIndexCmd::getValue)
                .forEach(indexObservable::onNext);

        // --- timecodes always include timestamp from system clock
        timecodeObservable.subscribe(tc -> {
            VideoIndex videoIndex = new VideoIndex(Optional.of(Instant.now()), Optional.empty(), Optional.of(tc));
            indexObservable.onNext(videoIndex);
        });

        configEventObservable.subscribe(ces ->
                configEventsToTimecode(ces).ifPresent(timecodeObservable::onNext));

    }

    public static QuadVideoIO open(String httpAddress) {
        QuadVideoIO quadVideoIO = new QuadVideoIO(httpAddress);
        quadVideoIO.send(QuadVideoCommands.CONNECT);
        return quadVideoIO;
    }

    @Override
    public void close() {

    }

    @Override
    public <A extends VideoCommand> void send(A videoCommand) {
        commandSubject.onNext(videoCommand);
    }

    @Override
    public Subject<VideoCommand, VideoCommand> getCommandSubject() {
        return commandSubject;
    }

    @Override
    public String getConnectionID() {
        return httpAddress;
    }

    @Override
    public Observable<QuadError> getErrorObservable() {
        return errorObservable;
    }

    @Override
    public Observable<QuadState> getStateObservable() {
        return statusObservable;
    }

    @Override
    public Observable<VideoIndex> getIndexObservable() {
        return indexObservable;
    }

    private void connect() {
        String request = ConnectionID.buildRequest(httpAddress);
        sendRequestWithErrorCheck(request).ifPresent(s -> {
            ConnectionID id = ConnectionID.fromJSON(s);
            statusObservable.onNext(new QuadState(id.getConnectionid()));
        });
    }

    /**
     * The KiPro returns timecode via config events
     */
    private void requestConfigEvent() {
        int cid = connectionID.get();
        String request = ConfigEvent.buildRequest(httpAddress, cid);
        Optional<String> response = sendRequestWithErrorCheck(request);
        ConfigEvent[] events = {};
        if (response.isPresent()) {
            try {
                events = ConfigEvent.fromJSON(response.get());
            }
            catch (Exception e) {
                log.debug("Failed to parse response. Response was: \n" + response);
            }
        }
        if (events.length > 0) {
            configEventObservable.onNext(events);
        }
    }

    private Optional<Timecode> configEventsToTimecode(ConfigEvent[] configEvents) {
        if (configEvents.length > 0) {
            return ConfigEvent.toTimecode(configEvents);
        }
        else {
            return Optional.empty();
        }
    }

    private Optional<String> sendRequestWithErrorCheck(String request) {
        Optional<String> json = Optional.empty();
        QuadError error = noError;
        try {
            HttpResponse<String> response = Unirest.get(request)
                    .header("Accept", "application/json")
                    .asString();
            if (response.getStatus() != 200) {
                error = new QuadError(true, true, Optional.empty(), Optional.empty());
            }
            else {
                json = Optional.ofNullable(response.getBody());
            }
        }
        catch (UnirestException e) {
            error = new QuadError(true, true, Optional.empty(), Optional.of(e));
        }
        errorObservable.onNext(error);
        return json;
    }

    public static String sendRequest(String request) {
        log.debug("REQUEST: {}", request);
        try {
            HttpResponse<String> response = Unirest.get(request)
                    .header("Accept", "application/json")
                    .asString();
            return response.getBody();
        }
        catch (UnirestException e) {
            throw new KiProException("Failed on request: " + request, e);
        }
    }

}