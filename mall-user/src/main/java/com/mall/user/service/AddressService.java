package com.mall.user.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
import com.mall.user.dto.AddressResponse;
import com.mall.user.dto.AddressSaveRequest;
import com.mall.user.entity.Address;
import com.mall.user.mapper.AddressMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AddressService {
    private final AddressMapper addressMapper;

    public AddressService(AddressMapper addressMapper) {
        this.addressMapper = addressMapper;
    }

    public List<AddressResponse> list(Long userId) {
        return addressMapper.selectList(new QueryWrapper<Address>()
                        .eq("user_id", userId)
                        .orderByDesc("is_default")
                        .orderByDesc("updated_at"))
                .stream().map(this::toDto).toList();
    }

    @Transactional
    public AddressResponse create(Long userId, AddressSaveRequest request) {
        boolean firstAddress = countByUser(userId) == 0;
        Address address = new Address();
        applyRequest(address, request);
        address.setUserId(userId);
        // 首条地址强制设为默认，保证下单确认页始终有可用地址
        address.setIsDefault(firstAddress || request.defaultRequested() ? 1 : 0);
        address.setCreatedAt(LocalDateTime.now());
        address.setUpdatedAt(LocalDateTime.now());
        addressMapper.insert(address);
        if (address.getIsDefault() == 1) {
            clearOtherDefaults(userId, address.getId());
        }
        return toDto(address);
    }

    @Transactional
    public AddressResponse update(Long userId, Long addressId, AddressSaveRequest request) {
        Address address = requiredOwned(userId, addressId);
        applyRequest(address, request);
        if (request.defaultRequested()) {
            address.setIsDefault(1);
        }
        address.setUpdatedAt(LocalDateTime.now());
        addressMapper.updateById(address);
        if (request.defaultRequested()) {
            clearOtherDefaults(userId, addressId);
        }
        return toDto(address);
    }

    public void delete(Long userId, Long addressId) {
        requiredOwned(userId, addressId);
        addressMapper.deleteById(addressId);
    }

    /** 内部接口：订单服务下单时取地址快照（校验属主，防止越权引用他人地址）。 */
    public AddressResponse ownedAddress(Long userId, Long addressId) {
        return toDto(requiredOwned(userId, addressId));
    }

    private long countByUser(Long userId) {
        return addressMapper.selectCount(new QueryWrapper<Address>().eq("user_id", userId));
    }

    private void clearOtherDefaults(Long userId, Long keepAddressId) {
        Address pattern = new Address();
        pattern.setIsDefault(0);
        pattern.setUpdatedAt(LocalDateTime.now());
        addressMapper.update(pattern, new QueryWrapper<Address>()
                .eq("user_id", userId)
                .ne("id", keepAddressId)
                .eq("is_default", 1));
    }

    private Address requiredOwned(Long userId, Long addressId) {
        Address address = addressMapper.selectById(addressId);
        if (address == null || !address.getUserId().equals(userId)) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        return address;
    }

    private void applyRequest(Address address, AddressSaveRequest request) {
        address.setReceiver(request.receiver().trim());
        address.setPhone(request.phone().trim());
        address.setProvince(request.province().trim());
        address.setCity(request.city().trim());
        address.setDistrict(request.district().trim());
        address.setDetail(request.detail().trim());
    }

    private AddressResponse toDto(Address address) {
        return new AddressResponse(address.getId(), address.getReceiver(), address.getPhone(),
                address.getProvince(), address.getCity(), address.getDistrict(), address.getDetail(),
                address.getIsDefault() != null && address.getIsDefault() == 1, address.getCreatedAt());
    }
}
