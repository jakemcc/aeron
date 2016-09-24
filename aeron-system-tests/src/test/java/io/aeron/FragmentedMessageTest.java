/*
 * Copyright 2014 - 2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron;

import org.agrona.DirectBuffer;
import org.junit.Test;
import org.junit.experimental.theories.DataPoint;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.agrona.concurrent.UnsafeBuffer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;

import static io.aeron.logbuffer.FrameDescriptor.END_FRAG_FLAG;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;


@RunWith(Theories.class)
public class FragmentedMessageTest
{
    @DataPoint
    public static final String UNICAST_CHANNEL = "aeron:udp?endpoint=localhost:54325";

    @DataPoint
    public static final String MULTICAST_CHANNEL = "aeron:udp?endpoint=224.20.30.39:54326|interface=localhost";

    @DataPoint
    public static final ThreadingMode SHARED = ThreadingMode.SHARED;

    @DataPoint
    public static final ThreadingMode SHARED_NETWORK = ThreadingMode.SHARED_NETWORK;

    @DataPoint
    public static final ThreadingMode DEDICATED = ThreadingMode.DEDICATED;

    public static final int STREAM_ID = 1;
    public static final int FRAGMENT_COUNT_LIMIT = 10;

    private final FragmentHandler mockFragmentHandler = mock(FragmentHandler.class);

    @Theory
    @Test(timeout = 10000)
    public void shouldReceivePublishedMessage(final String channel, final ThreadingMode threadingMode) throws Exception
    {
        final MediaDriver.Context ctx = new MediaDriver.Context();
        ctx.threadingMode(threadingMode);

        final FragmentAssembler adapter = new FragmentAssembler(mockFragmentHandler);

        try (MediaDriver ignore = MediaDriver.launch(ctx);
             Aeron aeron = Aeron.connect();
             Publication publication = aeron.addPublication(channel, STREAM_ID);
             Subscription subscription = aeron.addSubscription(channel, STREAM_ID))
        {
            final UnsafeBuffer srcBuffer = new UnsafeBuffer(new byte[ctx.mtuLength() * 4]);
            final int offset = 0;
            final int length = srcBuffer.capacity() / 4;

            for (int i = 0; i < 4; i++)
            {
                srcBuffer.setMemory(i * length, length, (byte)(65 + i));
            }

            while (publication.offer(srcBuffer, offset, srcBuffer.capacity()) < 0L)
            {
                Thread.yield();
            }

            final int expectedFragmentsBecauseOfHeader = 5;
            int numFragments = 0;
            do
            {
                numFragments += subscription.poll(adapter, FRAGMENT_COUNT_LIMIT);
            }
            while (numFragments < expectedFragmentsBecauseOfHeader);

            final ArgumentCaptor<DirectBuffer> bufferArg = ArgumentCaptor.forClass(DirectBuffer.class);
            final ArgumentCaptor<Header> headerArg = ArgumentCaptor.forClass(Header.class);

            verify(mockFragmentHandler, times(1)).onFragment(
                bufferArg.capture(), eq(offset), eq(srcBuffer.capacity()), headerArg.capture());

            final DirectBuffer capturedBuffer = bufferArg.getValue();
            for (int i = 0; i < srcBuffer.capacity(); i++)
            {
                assertThat("same at i=" + i, capturedBuffer.getByte(i), is(srcBuffer.getByte(i)));
            }

            assertThat(headerArg.getValue().flags(), is(END_FRAG_FLAG));
        }
        finally
        {
            ctx.deleteAeronDirectory();
        }
    }
}
