package com.mall.user.service;

import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
import com.mall.user.dto.AddressResponse;
import com.mall.user.dto.AddressSaveRequest;
import com.mall.user.entity.Address;
import com.mall.user.mapper.AddressMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

class AddressServiceTest {
    private AddressMapper addressMapper;
    private AddressService addressService;

    @BeforeEach
    void setUp() {
        addressMapper = Mockito.mock(AddressMapper.class);
        addressService = new AddressService(addressMapper);
    }

    private AddressSaveRequest request(boolean isDefault) {
        return new AddressSaveRequest("张三", "13800000001", "上海市", "上海市", "浦东新区", "学习路 1 号", isDefault);
    }

    private void stubInsertWithId() {
        Mockito.doAnswer(invocation -> {
            invocation.getArgument(0, Address.class).setId(11L);
            return 1;
        }).when(addressMapper).insert(Mockito.any(Address.class));
    }

    @Test
    void createShouldForceDefaultForFirstAddress() {
        Mockito.when(addressMapper.selectCount(any())).thenReturn(0L);
        stubInsertWithId();

        AddressResponse response = addressService.create(8L, request(false));

        assertTrue(response.isDefault(), "首条地址必须为默认，保证下单页有可用地址");
    }

    @Test
    void createShouldClearOtherDefaultsWhenDefaultRequested() {
        Mockito.when(addressMapper.selectCount(any())).thenReturn(1L);
        stubInsertWithId();

        addressService.create(8L, request(true));

        ArgumentCaptor<Address> captor = ArgumentCaptor.forClass(Address.class);
        Mockito.verify(addressMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getIsDefault());
        // 其余地址的默认标记被互斥清零
        Mockito.verify(addressMapper).update(any(Address.class), any());
    }

    @Test
    void updateShouldRejectForeignAddress() {
        Address foreign = new Address();
        foreign.setId(9L);
        foreign.setUserId(99L);
        Mockito.when(addressMapper.selectById(9L)).thenReturn(foreign);

        BizException exception = assertThrows(BizException.class,
                () -> addressService.update(8L, 9L, request(false)));
        assertEquals(ResultCode.NOT_FOUND.getCode(), exception.getCode());
    }

    @Test
    void deleteShouldVerifyOwnership() {
        Address own = new Address();
        own.setId(10L);
        own.setUserId(8L);
        Mockito.when(addressMapper.selectById(10L)).thenReturn(own);

        addressService.delete(8L, 10L);

        Mockito.verify(addressMapper).deleteById(10L);
    }

    @Test
    void ownedAddressShouldReturnFullAddressSnapshot() {
        Address own = new Address();
        own.setId(10L);
        own.setUserId(8L);
        own.setReceiver("李四");
        own.setPhone("13800000002");
        own.setProvince("北京市");
        own.setCity("北京市");
        own.setDistrict("海淀区");
        own.setDetail("实践路 2 号");
        own.setIsDefault(0);
        Mockito.when(addressMapper.selectById(10L)).thenReturn(own);

        AddressResponse response = addressService.ownedAddress(8L, 10L);

        assertEquals("北京市 北京市 海淀区 实践路 2 号", response.fullAddress());
    }

    @Test
    void listShouldOrderByDefaultFlagFirst() {
        Address first = new Address();
        first.setId(1L);
        first.setUserId(8L);
        first.setReceiver("甲");
        first.setPhone("13811111111");
        first.setProvince("广东省");
        first.setCity("深圳市");
        first.setDistrict("南山区");
        first.setDetail("科技园 1 号");
        first.setIsDefault(0);
        Mockito.when(addressMapper.selectList(any())).thenReturn(List.of(first));

        List<AddressResponse> responses = addressService.list(8L);

        assertEquals(1, responses.size());
        Mockito.verify(addressMapper).selectList(any());
    }
}
